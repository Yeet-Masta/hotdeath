// Command relay forwards text between the phone hosting a Hot Death game and
// up to three players, so people can play over the internet without opening
// ports on a phone. It knows nothing about the game: the host's phone runs it.
//
// Run:  go run .              (listens on :8080, or $PORT, or -addr)
// Clients connect to ws://HOST:PORT/ws. Put a TLS proxy (Caddy, nginx) in
// front and use wss:// for anything past your own network.
//
// Protocol, one WebSocket text message per line below:
//
//	host   -> relay  "#host"              relay -> host     "#room CODE"
//	player -> relay  "#join CODE"         relay -> player   "#joined"
//	                                      relay -> host     "#peer N"
//	host   -> relay  "N|payload"          relay -> player N "payload"
//	player -> relay  "payload"            relay -> host     "N|payload"
//	host   -> relay  "#kick N"            relay -> host     "#left N" (player gone)
//	relay  -> anyone "#error message"     relay -> player   "#closed" (host gone)
package main

import (
	"context"
	"crypto/rand"
	"flag"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const (
	maxPlayers   = 3 // besides the host
	maxRooms     = 2000
	maxMessage   = 64 << 10
	maxPerSecond = 60
	sendQueue    = 256
	pingEvery    = 30 * time.Second
	writeTimeout = 10 * time.Second
	codeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O or 1/I
	codeLength   = 5
)

// conn is one WebSocket. Fields other than ws and out are guarded by relay.mu.
type conn struct {
	ws     *websocket.Conn
	out    chan string
	room   *room
	peer   int // 0 = host
	closed bool

	rateSecond int64
	rateCount  int
}

type room struct {
	code  string
	host  *conn
	peers map[int]*conn
	next  int
}

type relay struct {
	mu    sync.Mutex
	rooms map[string]*room
}

func newRelay() *relay { return &relay{rooms: map[string]*room{}} }

// send queues text for c. A client too slow to keep up is disconnected.
// Caller holds r.mu.
func (r *relay) send(c *conn, text string) {
	if c.closed {
		return
	}
	select {
	case c.out <- text:
	default:
		log.Printf("dropping a client that is not reading")
		r.shut(c)
	}
}

// shut stops c after the messages already queued are written. Caller holds r.mu.
func (r *relay) shut(c *conn) {
	if c.closed {
		return
	}
	c.closed = true
	close(c.out)
}

func (r *relay) newCode() (string, error) {
	for {
		b := make([]byte, codeLength)
		for i := range b {
			n, err := rand.Int(rand.Reader, big.NewInt(int64(len(codeAlphabet))))
			if err != nil {
				return "", err
			}
			b[i] = codeAlphabet[n.Int64()]
		}
		if _, taken := r.rooms[string(b)]; !taken {
			return string(b), nil
		}
	}
}

// allow reports whether c is still under its message rate. Caller holds r.mu.
func allow(c *conn) bool {
	now := time.Now().Unix()
	if c.rateSecond != now {
		c.rateSecond, c.rateCount = now, 0
	}
	c.rateCount++
	return c.rateCount <= maxPerSecond
}

func (r *relay) handle(c *conn, text string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if c.closed || !allow(c) {
		return
	}
	switch {
	case c.room == nil:
		r.introduce(c, text)
	case c.peer == 0:
		r.fromHost(c.room, text)
	default:
		r.send(c.room.host, strconv.Itoa(c.peer)+"|"+text)
	}
}

// introduce handles a connection's first message: #host or #join CODE.
func (r *relay) introduce(c *conn, text string) {
	switch {
	case text == "#host":
		if len(r.rooms) >= maxRooms {
			r.send(c, "#error server is full, try again later")
			return
		}
		code, err := r.newCode()
		if err != nil {
			r.send(c, "#error could not open a room")
			return
		}
		rm := &room{code: code, host: c, peers: map[int]*conn{}, next: 1}
		r.rooms[code] = rm
		c.room, c.peer = rm, 0
		r.send(c, "#room "+code)
		log.Printf("room %s opened (%d open)", code, len(r.rooms))
	case strings.HasPrefix(text, "#join "):
		code := strings.ToUpper(strings.TrimSpace(strings.TrimPrefix(text, "#join ")))
		rm := r.rooms[code]
		switch {
		case rm == nil:
			r.send(c, "#error no game with code "+code)
		case len(rm.peers) >= maxPlayers:
			r.send(c, "#error that game is full")
		default:
			n := rm.next
			rm.next++
			rm.peers[n] = c
			c.room, c.peer = rm, n
			r.send(c, "#joined")
			r.send(rm.host, "#peer "+strconv.Itoa(n))
		}
	default:
		r.send(c, "#error send #host or #join CODE first")
	}
}

func (r *relay) fromHost(rm *room, text string) {
	if strings.HasPrefix(text, "#kick ") {
		n, _ := strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(text, "#kick ")))
		if p := rm.peers[n]; p != nil {
			delete(rm.peers, n)
			p.room = nil
			r.send(p, "#closed")
			r.shut(p)
		}
		return
	}
	if strings.HasPrefix(text, "#") {
		return
	}
	bar := strings.IndexByte(text, '|')
	if bar <= 0 {
		return
	}
	n, err := strconv.Atoi(text[:bar])
	if err != nil {
		return
	}
	if p := rm.peers[n]; p != nil {
		r.send(p, text[bar+1:])
	}
}

// leave removes c from its room when its connection ends.
func (r *relay) leave(c *conn) {
	r.mu.Lock()
	defer r.mu.Unlock()
	rm := c.room
	c.room = nil
	r.shut(c)
	if rm == nil {
		return
	}
	if c.peer == 0 {
		// The host left: the game is over for everyone in the room.
		for _, p := range rm.peers {
			p.room = nil
			r.send(p, "#closed")
			r.shut(p)
		}
		delete(r.rooms, rm.code)
		log.Printf("room %s closed (%d open)", rm.code, len(r.rooms))
		return
	}
	delete(rm.peers, c.peer)
	r.send(rm.host, "#left "+strconv.Itoa(c.peer))
}

// writer sends c's queue until it is shut, then closes the socket.
func writer(ctx context.Context, c *conn) {
	defer c.ws.Close(websocket.StatusNormalClosure, "")
	for text := range c.out {
		wctx, cancel := context.WithTimeout(ctx, writeTimeout)
		err := c.ws.Write(wctx, websocket.MessageText, []byte(text))
		cancel()
		if err != nil {
			c.ws.CloseNow()
			return
		}
	}
}

func pinger(ctx context.Context, c *conn) {
	t := time.NewTicker(pingEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			pctx, cancel := context.WithTimeout(ctx, writeTimeout)
			err := c.ws.Ping(pctx)
			cancel()
			if err != nil {
				c.ws.CloseNow()
				return
			}
		}
	}
}

func (r *relay) serve(w http.ResponseWriter, req *http.Request) {
	// Phones send no Origin header; browsers on other sites are refused.
	ws, err := websocket.Accept(w, req, nil)
	if err != nil {
		return
	}
	ws.SetReadLimit(maxMessage)
	ctx, cancel := context.WithCancel(req.Context())
	defer cancel()

	c := &conn{ws: ws, out: make(chan string, sendQueue)}
	go writer(ctx, c)
	go pinger(ctx, c)
	defer r.leave(c)

	for {
		typ, data, err := ws.Read(ctx)
		if err != nil {
			return // disconnected, or sent more than maxMessage bytes
		}
		if typ == websocket.MessageText {
			r.handle(c, string(data))
		}
	}
}

func handler(r *relay) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", r.serve)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprintln(w, "ok")
	})
	return mux
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	addr := flag.String("addr", ":"+port, "listen address")
	flag.Parse()

	srv := &http.Server{
		Addr:              *addr,
		Handler:           handler(newRelay()),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("Hot Death relay listening on %s", *addr)
	log.Fatal(srv.ListenAndServe())
}
