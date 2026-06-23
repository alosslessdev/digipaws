package neth.iecal.curbox.ui.fragments.main.reducers.analytics

import android.content.pm.PackageManager
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import neth.iecal.curbox.data.db.IntentLogEntity
import neth.iecal.curbox.databinding.ItemIntentLogBinding

class IntentsLogAdapter(private val onDelete: (Int) -> Unit) : ListAdapter<IntentLogEntity, IntentsLogAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val binding: ItemIntentLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemIntentLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.run {
            intentText.text = item.intentText
            timeText.text = DateUtils.getRelativeTimeSpanString(item.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            durationText.text = "Unlocked for ${item.unlockedDurationMs / 60_000} mins"

            val pm = root.context.packageManager
            try {
                val appInfo = pm.getApplicationInfo(item.packageName, 0)
                appName.text = pm.getApplicationLabel(appInfo)
                appIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
            } catch (e: PackageManager.NameNotFoundException) {
                appName.text = item.packageName
            }
            
            btnDelete.setOnClickListener {
                onDelete(item.id)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<IntentLogEntity>() {
        override fun areItemsTheSame(oldItem: IntentLogEntity, newItem: IntentLogEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: IntentLogEntity, newItem: IntentLogEntity): Boolean {
            return oldItem == newItem
        }
    }
}
