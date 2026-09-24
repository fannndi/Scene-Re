package com.omarea.ui.fps

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.omarea.model.FpsWatchSession
import com.omarea.ui.SearchHighlighter
import com.omarea.vtools.R
import java.text.SimpleDateFormat
import java.util.*

class AdapterSessions(private val context: Context, private val list: ArrayList<FpsWatchSession>) : RecyclerView.Adapter<AdapterSessions.ViewHolder>() {
    private var keywords: String = ""

    /**
     * Reserved for future async work (e.g. icon decoding). Kept so the owning
     * Activity can release adapter-held resources from onDestroy symmetrically
     * with the other list adapters.
     */
    fun destroy() {
    }

    fun getItem(position: Int): FpsWatchSession {
        return list[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    // private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    fun updateRow(position: Int, viewHolder: ViewHolder) {
        val item = getItem(position)

        viewHolder.itemTitle?.text = SearchHighlighter.highlight(item.appName, keywords)
        viewHolder.itemIcon?.setImageDrawable(item.appIcon)

        if (viewHolder.itemDesc != null)
            viewHolder.itemDesc?.text = dateFormat.format(Date(item.beginTime))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        internal var itemIcon: ImageView? = null
        internal var itemTitle: TextView? = null
        internal var itemDesc: TextView? = null
        internal var itemButton: ImageButton? = null
    }

    interface OnItemClickListener {
        fun onItemClick(position: Int)
    }

    override fun getItemCount(): Int {
        return list.size ?: 0
    }
    private var onItemClickListener: OnItemClickListener? = null
    private var onItemDeleteClickListener: OnItemClickListener? = null

    //提供setter方法
    fun setOnItemClickListener(onItemClickListener: OnItemClickListener?) {
        this.onItemClickListener = onItemClickListener
    }
    //提供setter方法
    fun setOnItemDeleteClickListener(onItemClickListener: OnItemClickListener?) {
        this.onItemDeleteClickListener = onItemClickListener
    }

    fun removeItem(position: Int) {
        if (position !in list.indices) {
            return
        }
        this.list.removeAt(position)
        // RecyclerView can animate a single removal precisely; the previous
        // notifyDataSetChanged() re-bound every visible row and cancelled the
        // default item animation.
        notifyItemRemoved(position)
        if (position < list.size) {
            notifyItemRangeChanged(position, list.size - position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // val convertView = View.inflate(context, R.layout.list_item_fps, null)
        val convertView = LayoutInflater.from(context).inflate(R.layout.list_item_fps, parent, false)
        val viewHolder = ViewHolder(convertView)
        viewHolder.itemIcon = convertView.findViewById(R.id.ItemIcon)
        viewHolder.itemTitle = convertView.findViewById(R.id.ItemTitle)
        viewHolder.itemDesc = convertView.findViewById(R.id.ItemDesc)
        viewHolder.itemButton = convertView.findViewById(R.id.download)

        return viewHolder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.itemView.run {
            setOnClickListener {
                onItemClickListener?.onItemClick(position)
            }
        }
        holder.itemButton?.run {
            setOnClickListener {
                onItemDeleteClickListener?.onItemClick(position)
            }
        }

        this.updateRow(position, holder)
    }
}
