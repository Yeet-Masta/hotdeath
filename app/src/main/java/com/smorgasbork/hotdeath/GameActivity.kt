package com.smorgasbork.hotdeath

import android.app.Dialog
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
import com.smorgasbork.hotdeath.engine.Prompt
import com.smorgasbork.hotdeath.engine.Seat
import com.smorgasbork.hotdeath.net.GameSession
import com.smorgasbork.hotdeath.net.PlayerAction
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
			onAction = { session.act(it) }
			onCardHelp = ::showCardHelp
			nameOf = ::nameOf
			onLayoutReady = { vm.start() }
		}
		setContentView(buildLayout())
		bindMenuButtons()

		table.bind(view, familyFriendly)
		session.listener = SessionListener(::onUpdate)
		session.onStatus = ::onStatus
		syncControls()
		table.requestFocus()
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
			session.listener = null
			session.onStatus = null
			message.removeCallbacks(hideMessage)
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
		if (e is ViewEvent.Message) showMessage(CardText.message(this, e.message, familyFriendly, ::nameOf))
		syncControls()
	}

	private fun onStatus(s: SessionStatus) {
		when (s) {
			is SessionStatus.PlayerLeft ->
				showMessage(getString(R.string.mp_left, s.name ?: CardText.seatName(this, s.seat)))
			is SessionStatus.HostLost -> {
				promptDialog?.dismiss()
				AlertDialog.Builder(this)
					.setMessage(getString(R.string.mp_host_lost) + (s.reason?.let { "\n($it)" } ?: ""))
					.setCancelable(false)
					.setPositiveButton(android.R.string.ok) { _, _ -> finish() }
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

		root.addView(table)

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
