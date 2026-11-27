package com.smorgasbork.hotdeath

import android.app.Dialog
import android.content.Intent
import android.os.SystemClock
import android.widget.LinearLayout
import androidx.activity.addCallback
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.RelativeLayout.LayoutParams
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.smorgasbork.hotdeath.engine.CardDeck
import com.smorgasbork.hotdeath.engine.CardType
import com.smorgasbork.hotdeath.engine.GameMessage
import com.smorgasbork.hotdeath.engine.Prompt
import com.smorgasbork.hotdeath.engine.Seat
import com.smorgasbork.hotdeath.net.ClientSession
import com.smorgasbork.hotdeath.net.GameSession
import com.smorgasbork.hotdeath.net.PlayerAction
import com.smorgasbork.hotdeath.net.HostSession
import com.smorgasbork.hotdeath.net.SessionListener
import com.smorgasbork.hotdeath.net.SessionStatus
import com.smorgasbork.hotdeath.net.TableView
import com.smorgasbork.hotdeath.net.ViewEvent
import kotlin.math.max

class GameActivity : AppCompatActivity() {

	companion object {
		const val STARTUP_MODE = "com.smorgasbork.hotdeath.startup_mode"
		const val STARTUP_MODE_NEW = 1
		const val STARTUP_MODE_CONTINUE = 2
		const val STARTUP_MODE_MULTIPLAYER = 3

		private const val COLOR_ENABLED_TEXT = 0xffffffff.toInt()
		private const val COLOR_DISABLED_TEXT = 0xff7f7f7f.toInt()
		private const val MIN_MESSAGE_MS = 1500L
		/** Seconds left at which your clock turns red and buzzes. */
		private const val WARN_SECONDS = 5

		private fun setButtonEnabled(btn: Button, enabled: Boolean) {
			btn.isEnabled = enabled
			btn.setTextColor(if (enabled) COLOR_ENABLED_TEXT else COLOR_DISABLED_TEXT)
		}
	}

	private lateinit var vm: GameViewModel
	private lateinit var session: GameSession
	private lateinit var table: GameTable
	private val familyFriendly: Boolean get() = vm.config.familyFriendly
	private val view: TableView? get() = session.view

	private lateinit var menuPanel: View
	private lateinit var btnFastForward: Button
	private lateinit var btnNextRound: Button
	private lateinit var btnMenuDraw: Button
	private lateinit var btnMenuPass: Button
	private lateinit var message: TextView
	private lateinit var clock: TextView
	private lateinit var haptics: Haptics

	/** When the clock shown runs out, in [SystemClock.elapsedRealtime] time; 0 = no clock. */
	private var clockEnds = 0L
	private var clockWarned = false
	private val tickClock = object : Runnable {
		override fun run() = showClock()
	}

	private var dlgCardCatalog: Dialog? = null
	private var dlgCardHelp: Dialog? = null
	private var promptDialog: Dialog? = null
	private var promptShown: Prompt? = null

	private val hideMessage = Runnable { message.visibility = View.INVISIBLE }

	// ── lifecycle ─────────────────────────────────────────────────────────────

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val mode = intent.getIntExtra(STARTUP_MODE, STARTUP_MODE_NEW)
		vm = ViewModelProvider(this, GameViewModel.factory(application, mode))[GameViewModel::class.java]
		session = vm.session ?: run {
			// A multiplayer game cannot survive the app process being killed.
			Toast.makeText(this, R.string.mp_ended, Toast.LENGTH_LONG).show()
			finish()
			return
		}
		if (vm.multiplayer) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		table = GameTable(this).apply {
			id = View.generateViewId()
			bottomMarginExternal = dpToPx(58)
			onAction = ::onTableAction
			onCardHelp = ::showCardHelp
			nameOf = ::nameOf
			onLayoutReady = { vm.start() }
		}
		setContentView(buildLayout())
		bindMenuButtons()

		haptics = Haptics(table) { vm.config.haptics }

		table.bind(view, familyFriendly)
		vm.ui = SessionListener(::onUpdate)
		session.onStatus = ::onStatus
		syncControls()
		syncClock()
		table.requestFocus()

		onBackPressedDispatcher.addCallback(this) { confirmLeave() }
	}

	override fun onPause() {
		dlgCardHelp?.dismiss()
		dlgCardCatalog?.dismiss()
		// If the process dies, recreating this screen should continue the saved game.
		if (!vm.multiplayer) intent.putExtra(STARTUP_MODE, STARTUP_MODE_CONTINUE)
		super.onPause()
		vm.pause()
		vm.save()
	}

	override fun onResume() {
		super.onResume()
		vm.resume()
	}

	override fun onDestroy() {
		if (::table.isInitialized) {
			vm.ui = null
			session.onStatus = null
			message.removeCallbacks(hideMessage)
			clock.removeCallbacks(tickClock)
		}
		promptDialog?.dismiss()
		dlgCardHelp?.dismiss()
		dlgCardCatalog?.dismiss()
		super.onDestroy()
	}

	// ── game updates ──────────────────────────────────────────────────────────

	private fun nameOf(seat: Seat): String = view?.name(seat) ?: CardText.seatName(this, seat)

	private fun onUpdate(v: TableView, e: ViewEvent?) {
		table.update(v, e)
		if (e is ViewEvent.Message) {
			showMessage(CardText.message(this, e.message, familyFriendly, ::nameOf))
			buzzFor(e.message, v.me)
		}
		syncControls()
		syncClock()
		if (v.gameOver) Rejoin.clear(this)
	}

	private fun buzzFor(m: GameMessage, me: Seat) {
		when (m) {
			is GameMessage.YourPlay -> haptics.yourTurn()
			is GameMessage.CardNoGood -> haptics.reject()
			is GameMessage.TakesPenalty -> if (m.seat == me) haptics.hit()
			is GameMessage.SendingPenalty -> if (m.victim == me) haptics.hit()
			is GameMessage.TimedOut -> if (m.seat == me) haptics.hit()
			else -> {}
		}
	}

	private fun onTableAction(a: PlayerAction) {
		// Tapping the discard pile mid-round shows what was played.
		if (a == PlayerAction.DiscardTap && view?.waitingToStartRound != true) showHistory()
		else session.act(a)
	}

	private fun onStatus(s: SessionStatus) {
		when (s) {
			is SessionStatus.PlayerLeft ->
				showMessage(getString(R.string.mp_left, s.name ?: CardText.seatName(this, s.seat)))
			is SessionStatus.PlayerBack ->
				showMessage(getString(R.string.mp_back, s.name ?: CardText.seatName(this, s.seat)))
			is SessionStatus.HostLost -> {
				promptDialog?.dismiss()
				val canRejoin = (session as? ClientSession)?.rejoinToken != null && view?.gameOver != true && Rejoin.saved(this)
				AlertDialog.Builder(this)
					.setMessage(getString(R.string.mp_host_lost) + (s.reason?.let { "\n($it)" } ?: ""))
					.setCancelable(false)
					.setPositiveButton(if (canRejoin) R.string.mp_rejoin else android.R.string.ok) { _, _ ->
						if (canRejoin) startActivity(Intent(this, MultiplayerActivity::class.java).putExtra(MultiplayerActivity.EXTRA_REJOIN, true))
						finish()
					}
					.apply { if (canRejoin) setNegativeButton(R.string.mp_quit) { _, _ -> Rejoin.clear(this@GameActivity); finish() } }
					.show()
			}
		}
	}

	/** Brings buttons and dialogs in line with the table. */
	private fun syncControls() {
		val v = view
		btnNextRound.visibility = if (v?.showNextRound == true) View.VISIBLE else View.INVISIBLE
		btnFastForward.visibility = if (v?.showFastForward == true) View.VISIBLE else View.INVISIBLE
		if (v?.showMenu == true) {
			setButtonEnabled(btnMenuDraw, v.canDraw)
			setButtonEnabled(btnMenuPass, v.canPass)
			menuPanel.visibility = View.VISIBLE
		} else {
			menuPanel.visibility = View.INVISIBLE
		}
		syncPrompt(v?.prompt)
	}

	private fun syncPrompt(p: Prompt?) {
		if (p == promptShown) return
		promptDialog?.dismiss()
		promptDialog = null
		promptShown = p
		promptDialog = when (p) {
			Prompt.NumCardsToDeal -> AlertDialog.Builder(this)
				.setCancelable(false)
				.setTitle(R.string.prompt_deal)
				.setItems(R.array.deal_values) { _, i -> session.act(PlayerAction.Deal(i + 5)) }
				.show()
			is Prompt.Victim -> AlertDialog.Builder(this)
				.setCancelable(false)
				.setTitle(R.string.prompt_victim)
				.setItems(p.options.map(::nameOf).toTypedArray()) { _, i ->
					session.act(PlayerAction.Victim(p.options[i]))
				}
				.show()
			// The color prompt is drawn on the table itself.
			Prompt.Color, null -> null
		}
	}

	/**
	 * Shows a game message over the table. It replaces the old Toast, whose
	 * position is ignored on current Android and whose duration was computed
	 * from the game speed in a way Toast does not support.
	 */
	private fun showMessage(text: String) {
		message.text = text
		message.visibility = View.VISIBLE
		// Sit just above the player's cards once the text is measured.
		message.post { message.translationY = (table.messageAnchorY - message.height).toFloat() }
		message.removeCallbacks(hideMessage)
		message.postDelayed(hideMessage, max(MIN_MESSAGE_MS, (view?.delayMs ?: 0) + 500))
	}

	// ── turn clock ────────────────────────────────────────────────────────────

	private fun syncClock() {
		val v = view
		val seat = v?.clockSeat
		clockEnds = if (seat == null) 0L else SystemClock.elapsedRealtime() + v.clockLeftMs
		if (seat != v?.me || seat == null) clockWarned = false
		showClock()
	}

	private fun showClock() {
		clock.removeCallbacks(tickClock)
		val v = view
		val seat = v?.clockSeat
		if (seat == null || clockEnds == 0L) {
			clock.visibility = View.GONE
			return
		}
		val left = (clockEnds - SystemClock.elapsedRealtime()).coerceAtLeast(0)
		val secs = ((left + 999) / 1000).toInt()
		clock.text = if (seat == v.me) getString(R.string.clock_you, secs) else getString(R.string.clock_other, nameOf(seat), secs)
		clock.setTextColor(if (seat == v.me && secs <= WARN_SECONDS) 0xffff5050.toInt() else Color.WHITE)
		clock.visibility = View.VISIBLE
		if (seat == v.me && secs <= WARN_SECONDS && !clockWarned) {
			clockWarned = true
			haptics.warn()
		}
		if (left > 0) clock.postDelayed(tickClock, 250)
	}

	// ── leaving ───────────────────────────────────────────────────────────────

	private fun confirmLeave() {
		val v = view
		if (v == null || v.gameOver) return finish()
		val msg = when {
			!vm.multiplayer -> R.string.leave_saved
			session is HostSession -> R.string.leave_host
			else -> R.string.leave_client
		}
		AlertDialog.Builder(this)
			.setMessage(msg)
			.setPositiveButton(R.string.leave) { _, _ ->
				if (vm.multiplayer) Rejoin.clear(this)
				finish()
			}
			.setNegativeButton(R.string.stay, null)
			.show()
	}

	// ── layout ────────────────────────────────────────────────────────────────

	private fun buildLayout(): RelativeLayout {
		val root = RelativeLayout(this)
		val btnWidth = dpToPx(160)
		val btnHeight = dpToPx(42)

		btnNextRound = actionButton(R.string.lbl_next_round) { session.act(PlayerAction.NextRound) }
		btnFastForward = actionButton(R.string.lbl_fast_forward) { session.act(PlayerAction.FastForward) }

		menuPanel = layoutInflater.inflate(R.layout.options_menu, root, false).apply {
			id = View.generateViewId()
			visibility = View.INVISIBLE
		}

		message = TextView(this).apply {
			setTextColor(Color.WHITE)
			setBackgroundColor(0x99000000.toInt())
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
			gravity = Gravity.CENTER
			val pad = dpToPx(8)
			setPadding(pad, pad / 2, pad, pad / 2)
			visibility = View.INVISIBLE
			// Read new messages aloud with TalkBack.
			accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
		}

		clock = TextView(this).apply {
			setTextColor(Color.WHITE)
			setBackgroundColor(0x99000000.toInt())
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			val pad = dpToPx(6)
			setPadding(pad, pad / 2, pad, pad / 2)
			visibility = View.GONE
		}

		root.addView(table)

		root.addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
			addRule(RelativeLayout.ALIGN_PARENT_TOP)
			addRule(RelativeLayout.ALIGN_PARENT_LEFT)
			setMargins(dpToPx(8), dpToPx(8), 0, 0)
		})

		root.addView(message, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
			addRule(RelativeLayout.ALIGN_PARENT_TOP)
			addRule(RelativeLayout.CENTER_HORIZONTAL)
		})

		root.addView(menuPanel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
			addRule(RelativeLayout.ALIGN_BOTTOM, table.id)
			addRule(RelativeLayout.CENTER_HORIZONTAL)
			bottomMargin = dpToPx(8)
		})

		root.addView(btnFastForward, LayoutParams(btnWidth, btnHeight).apply {
			addRule(RelativeLayout.ABOVE, menuPanel.id)
			addRule(RelativeLayout.CENTER_HORIZONTAL)
			bottomMargin = dpToPx(8)
		})

		root.addView(btnNextRound, LayoutParams(btnWidth, btnHeight).apply {
			addRule(RelativeLayout.CENTER_IN_PARENT)
			topMargin = dpToPx(8)
		})

		root.fitsSystemWindows = true
		return root
	}

	private fun actionButton(label: Int, onClick: () -> Unit): Button =
		(layoutInflater.inflate(R.layout.action_button, null) as Button).apply {
			text = getString(label)
			id = View.generateViewId()
			visibility = View.INVISIBLE
			setOnClickListener {
				visibility = View.INVISIBLE
				onClick()
			}
		}

	private fun bindMenuButtons() {
		btnMenuDraw = findViewById(R.id.btn_menu_draw)
		btnMenuPass = findViewById(R.id.btn_menu_pass)
		btnMenuDraw.setOnClickListener { session.act(PlayerAction.Draw) }
		btnMenuPass.setOnClickListener { session.act(PlayerAction.Pass) }
		findViewById<Button>(R.id.btn_menu_help).setOnClickListener { showCardCatalog() }
	}

	private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

	// ── dialogs ───────────────────────────────────────────────────────────────

	private fun showCardHelp(type: CardType) {
		val dlg = dlgCardHelp ?: TapDismissableDialog(this).also {
			it.setContentView(R.layout.dlg_card_help)
			dlgCardHelp = it
		}
		dlg.setTitle(CardText.cardName(this, type, familyFriendly))
		dlg.findViewById<TextView>(R.id.text)?.setText(CardText.help(type, familyFriendly))
		dlg.findViewById<ImageView>(R.id.image)?.setImageResource(CardText.drawable(type, familyFriendly))
		dlg.show()
	}

	private fun showHistory() {
		val v = view ?: return
		val deck = CardDeck(v.deckType)
		val plays = vm.recorder.recent.take(vm.config.historyCount)
		val list = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			val pad = dpToPx(16)
			setPadding(pad, pad / 2, pad, 0)
		}
		if (plays.isEmpty()) {
			list.addView(TextView(this).apply { setText(R.string.history_empty) })
		}
		for (p in plays) {
			val t = deck[p.card]?.type ?: continue
			val row = LinearLayout(this).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
				setPadding(0, dpToPx(4), 0, dpToPx(4))
			}
			row.addView(ImageView(this).apply {
				setImageResource(CardText.drawable(t, familyFriendly))
				adjustViewBounds = true
			}, LinearLayout.LayoutParams(dpToPx(36), dpToPx(54)))
			row.addView(TextView(this).apply {
				val card = CardText.cardName(this@GameActivity, t, familyFriendly)
				text = p.from?.let { getString(R.string.history_played, nameOf(it), card) } ?: getString(R.string.history_turned_up, card)
				setPadding(dpToPx(12), 0, 0, 0)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
			})
			row.setOnClickListener { showCardHelp(t) }
			list.addView(row)
		}
		AlertDialog.Builder(this)
			.setTitle(R.string.history_title)
			.setView(android.widget.ScrollView(this).apply { addView(list) })
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	private fun showCardCatalog() {
		val v = view ?: return
		val dlg = dlgCardCatalog ?: Dialog(this).also { d ->
			d.requestWindowFeature(Window.FEATURE_NO_TITLE)
			d.setContentView(R.layout.dlg_card_catalog)
			// One entry per distinct card in this game's deck, in deck order.
			val types = CardDeck(v.deckType).cards.map { it.type }.distinct()
			d.findViewById<GridView>(R.id.gridview).apply {
				adapter = CardImageAdapter(this@GameActivity, types, familyFriendly)
				setOnItemClickListener { _, _, position, _ -> showCardHelp(types[position]) }
			}
			dlgCardCatalog = d
		}
		dlg.show()
	}
}
