package com.formationtool.app.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.formationtool.app.R
import com.formationtool.app.model.Dancer

/**
 * 舞者列表适配器
 */
class DancerAdapter(
    private val onDancerClick: (Dancer) -> Unit,
    private val onDancerLongPress: (Dancer) -> Unit,
    private val onDeleteClick: (Dancer) -> Unit
) : RecyclerView.Adapter<DancerAdapter.DancerViewHolder>() {

    private var dancers: List<Dancer> = emptyList()

    fun submitList(newList: List<Dancer>) {
        dancers = newList.toList()  // 复制一份新列表
        notifyDataSetChanged()
        android.util.Log.d("DancerAdapter", "更新舞者列表，数量: ${dancers.size}")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DancerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dancer, parent, false)
        return DancerViewHolder(view)
    }

    override fun onBindViewHolder(holder: DancerViewHolder, position: Int) {
        holder.bind(dancers[position])
    }

    override fun getItemCount(): Int = dancers.size

    inner class DancerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorIndicator: View = itemView.findViewById(R.id.colorIndicator)
        private val tvNumber: TextView = itemView.findViewById(R.id.tvDancerNumber)
        private val tvName: TextView = itemView.findViewById(R.id.tvDancerName)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteDancer)

        fun bind(dancer: Dancer) {
            // 设置名字
            tvName.text = dancer.name

            // 提取编号（从"舞者1"提取"1"）
            val number = dancer.name.replace("舞者", "").ifEmpty { "?" }
            tvNumber.text = number

            // 设置圆形颜色
            val drawable = colorIndicator.background as? android.graphics.drawable.GradientDrawable
            drawable?.setColor(dancer.color)

            // 点击选中
            itemView.setOnClickListener {
                onDancerClick(dancer)
            }

            // 长按编辑
            itemView.setOnLongClickListener {
                onDancerLongPress(dancer)
                true
            }

            // 删除按钮
            btnDelete.setOnClickListener {
                onDeleteClick(dancer)
            }

            // 选中状态：背景高亮
            itemView.alpha = if (dancer.isSelected) 1.0f else 0.7f
            itemView.setBackgroundColor(
                if (dancer.isSelected)
                    Color.parseColor("#3A3A4F")
                else
                    Color.parseColor("#2A2A3F")
            )
        }
    }
}
