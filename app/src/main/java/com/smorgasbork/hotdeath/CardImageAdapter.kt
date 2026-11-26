package com.smorgasbork.hotdeath

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import com.smorgasbork.hotdeath.engine.CardType

/** Thumbnails for the card catalog grid. */
class CardImageAdapter(
	private val context: Context,
	private val types: List<CardType>,
	private val familyFriendly: Boolean,
) : BaseAdapter() {

	override fun getCount(): Int = types.size

	override fun getItem(position: Int): CardType = types[position]

	override fun getItemId(position: Int): Long = position.toLong()

	override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
		val imageView = convertView as? ImageView ?: ImageView(context).apply {
			val scale = resources.displayMetrics.density
			layoutParams = AbsListView.LayoutParams((52 * scale + 0.5f).toInt(), (80 * scale + 0.5f).toInt())
			scaleType = ImageView.ScaleType.FIT_CENTER
			setPadding(8, 8, 8, 8)
		}
		val t = types[position]
		imageView.setImageResource(CardText.drawable(t, familyFriendly))
		imageView.contentDescription = CardText.cardName(context, t, familyFriendly)
		return imageView
	}
}
