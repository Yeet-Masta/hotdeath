package main

import (
	"context"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

type client struct {
	t  *testing.T
	ws *websocket.Conn
}

func dial(t *testing.T, srv *httptest.Server) *client {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http")+"/ws", nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	ws.SetReadLimit(maxMessage * 2)
	t.Cleanup(func() { ws.CloseNow() })
	return &client{t, ws}
}

func (c *client) say(text string) {
	c.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := c.ws.Write(ctx, websocket.MessageText, []byte(text)); err != nil {
		c.t.Fatalf("write %q: %v", text, err)
	}
}

func (c *client) hear() (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, b, err := c.ws.Read(ctx)
	return string(b), err
}

func (c *client) expect(want string) {
	c.t.Helper()
	got, err := c.hear()
	if err != nil {
		c.t.Fatalf("waiting for %q: %v", want, err)
	}
	if got != want {
		c.t.Fatalf("got %q, want %q", got, want)
	}
}

func (c *client) expectClosed() {
	c.t.Helper()
	if got, err := c.hear(); err == nil {
		c.t.Fatalf("expected the connection to close, got %q", got)
	}
}

func openRoom(t *testing.T, srv *httptest.Server) (*client, string) {
	host := dial(t, srv)
	host.say("#host")
	msg, err := host.hear()
	if err != nil || !strings.HasPrefix(msg, "#room ") {
		t.Fatalf("no room: %q %v", msg, err)
	}
	code := strings.TrimPrefix(msg, "#room ")
	if len(code) != codeLength {
		t.Fatalf("bad code %q", code)
	}
	return host, code
}

func join(t *testing.T, srv *httptest.Server, host *client, code string, n int) *client {
	p := dial(t, srv)
	p.say("#join " + strings.ToLower(code))
	p.expect("#joined")
	host.expect("#peer " + string(rune('0'+n)))
	return p
}

func TestRelayForwardsBothWays(t *testing.T) {
	srv := httptest.NewServer(handler(newRelay()))
	defer srv.Close()
	host, code := openRoom(t, srv)
	p1 := join(t, srv, host, code, 1)
	p2 := join(t, srv, host, code, 2)

	host.say(`1|{"t":"start"}`)
	p1.expect(`{"t":"start"}`)
	host.say("2|a|b") // only the first bar separates the peer number
	p2.expect("a|b")

	p2.say(`{"t":"a"}`)
	host.expect(`2|{"t":"a"}`)
	p1.say("#join XXXXX") // after joining, everything a player sends is data
	host.expect("1|#join XXXXX")
}

func TestRoomLimitsAndErrors(t *testing.T) {
	srv := httptest.NewServer(handler(newRelay()))
	defer srv.Close()
	host, code := openRoom(t, srv)
	for n := 1; n <= maxPlayers; n++ {
		join(t, srv, host, code, n)
	}
	late := dial(t, srv)
	late.say("#join " + code)
	late.expect("#error that game is full")

	lost := dial(t, srv)
	lost.say("#join ZZZZZ")
	lost.expect("#error no game with code ZZZZZ")

	rude := dial(t, srv)
	rude.say("hello")
	rude.expect("#error send #host or #join CODE first")
}

func TestHostLeavingClosesTheRoom(t *testing.T) {
	srv := httptest.NewServer(handler(newRelay()))
	defer srv.Close()
	host, code := openRoom(t, srv)
	p := join(t, srv, host, code, 1)
	host.ws.Close(websocket.StatusNormalClosure, "")
	p.expect("#closed")
	p.expectClosed()

	again := dial(t, srv)
	again.say("#join " + code)
	again.expect("#error no game with code " + code)
}

func TestPlayerLeavingAndKick(t *testing.T) {
	srv := httptest.NewServer(handler(newRelay()))
	defer srv.Close()
	host, code := openRoom(t, srv)
	p1 := join(t, srv, host, code, 1)
	p2 := join(t, srv, host, code, 2)

	p1.ws.Close(websocket.StatusNormalClosure, "")
	host.expect("#left 1")

	host.say("#kick 2")
	p2.expect("#closed")
	p2.expectClosed()

	// The freed seats can be taken again.
	join(t, srv, host, code, 3)
}

func TestOversizedMessageDropsTheSender(t *testing.T) {
	srv := httptest.NewServer(handler(newRelay()))
	defer srv.Close()
	host, code := openRoom(t, srv)
	p := join(t, srv, host, code, 1)
	p.say(strings.Repeat("x", maxMessage+1))
	p.expectClosed()
	host.expect("#left 1")
}
