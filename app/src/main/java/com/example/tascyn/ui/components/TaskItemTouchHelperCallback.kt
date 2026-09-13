package com.example.tascyn.ui.components

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.tascyn.R
import com.example.tascyn.ui.adapter.QuadrantGroupedTaskAdapter
import com.example.tascyn.ui.adapter.QuadrantListItem
import com.example.tascyn.ui.adapter.TodaySectionTaskAdapter

class TaskItemTouchHelperCallback(
    private val context: Context,
    private val onSwipeRight: (position: Int) -> Unit,
    private val onSwipeLeft: (position: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val completeIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_precision_check)
    private val deleteIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_trash_precision)

    override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        // Prevent swiping header items in grouped recycler
        if (viewHolder is QuadrantGroupedTaskAdapter.HeaderViewHolder) {
            return 0
        }
        return super.getSwipeDirs(recyclerView, viewHolder)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val pos = viewHolder.adapterPosition
        if (pos == RecyclerView.NO_POSITION) return

        if (direction == ItemTouchHelper.RIGHT) {
            onSwipeRight(pos)
        } else if (direction == ItemTouchHelper.LEFT) {
            onSwipeLeft(pos)
        }
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView
        val cornerRadius = 24f

        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            if (dX > 0) {
                // 1. SWIPE RIGHT -> COMPLETE (GREEN)
                bgPaint.color = Color.parseColor("#10B981")
                val rectF = RectF(
                    itemView.left.toFloat(),
                    itemView.top.toFloat() + 4f,
                    itemView.left.toFloat() + dX,
                    itemView.bottom.toFloat() - 4f
                )
                c.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint)

                // Draw Check Icon + Text
                completeIcon?.let { icon ->
                    icon.setTint(Color.WHITE)
                    val iconSize = 48
                    val iconMargin = (itemView.height - iconSize) / 2
                    val iconTop = itemView.top + iconMargin
                    val iconBottom = iconTop + iconSize
                    val iconLeft = itemView.left + 36
                    val iconRight = iconLeft + iconSize
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)

                    if (dX > 80) {
                        icon.draw(c)
                        c.drawText("Complete", (iconRight + 20).toFloat(), (iconTop + iconSize * 0.72f).toFloat(), textPaint)
                    }
                }

            } else if (dX < 0) {
                // 2. SWIPE LEFT -> LEAVE / DELETE (RED)
                bgPaint.color = Color.parseColor("#EF4444")
                val rectF = RectF(
                    itemView.right.toFloat() + dX,
                    itemView.top.toFloat() + 4f,
                    itemView.right.toFloat(),
                    itemView.bottom.toFloat() - 4f
                )
                c.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint)

                // Draw Trash / Leave Icon + Text
                deleteIcon?.let { icon ->
                    icon.setTint(Color.WHITE)
                    val iconSize = 48
                    val iconMargin = (itemView.height - iconSize) / 2
                    val iconTop = itemView.top + iconMargin
                    val iconBottom = iconTop + iconSize
                    val iconRight = itemView.right - 36
                    val iconLeft = iconRight - iconSize
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)

                    if (Math.abs(dX) > 80) {
                        icon.draw(c)
                        val textWidth = textPaint.measureText("Leave Task")
                        c.drawText("Leave Task", (iconLeft - textWidth - 20).toFloat(), (iconTop + iconSize * 0.72f).toFloat(), textPaint)
                    }
                }
            }
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
