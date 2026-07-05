package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared

import android.content.DialogInterface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.TimeInterval
import neth.iecal.curbox.data.models.fixOvernightInterval
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.DayAdapter
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.DayItem
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.TimeIntervalAdapter

abstract class BaseTimeSettingsFragment : BottomSheetDialogFragment() {

    protected open val daysOfWeek = listOf(
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    )

    private lateinit var switchEveryDay: CompoundButton
    private lateinit var everydayContainer: View
    private lateinit var btnAddEverydayInterval: View
    private lateinit var everydayIntervalsContainer: RecyclerView
    private lateinit var daysListContainer: RecyclerView

    private val everydayIntervals = mutableListOf<TimeInterval>()
    private lateinit var everydayAdapter: TimeIntervalAdapter

    private val dayItems = mutableListOf<DayItem>()
    private lateinit var daysAdapter: DayAdapter

    protected abstract fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View
    protected abstract fun getTimeConfig(): AppTimeConfig
    protected abstract fun saveTimeConfig(config: AppTimeConfig)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflateView(inflater, container)
        switchEveryDay = root.findViewById(R.id.switch_every_day)
        everydayContainer = root.findViewById(R.id.everydayContainer)
        btnAddEverydayInterval = root.findViewById(R.id.btn_add_everyday_interval)
        everydayIntervalsContainer = root.findViewById(R.id.everydayIntervalsContainer)
        daysListContainer = root.findViewById(R.id.daysListContainer)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerViews()

        switchEveryDay.setOnCheckedChangeListener { _, isChecked ->
            everydayContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            daysAdapter.isInteractionEnabled = !isChecked
        }

        btnAddEverydayInterval.setOnClickListener {
            everydayIntervals.add(TimeInterval(9, 0, 17, 0))
            everydayAdapter.notifyItemInserted(everydayIntervals.size - 1)
        }

        loadExistingSettings()
    }

    override fun onDismiss(dialog: DialogInterface) {
        persistSettings()
        super.onDismiss(dialog)
    }

    private fun setupRecyclerViews() {
        everydayAdapter = TimeIntervalAdapter(
            everydayIntervals,
            onTimeClick = { interval, isStart, position ->
                showTimePicker(interval, isStart, everydayIntervals) { everydayAdapter.notifyItemChanged(position) }
            },
            onRemove = { position ->
                everydayIntervals.removeAt(position)
                everydayAdapter.notifyItemRemoved(position)
            }
        )
        everydayIntervalsContainer.layoutManager = LinearLayoutManager(requireContext())
        everydayIntervalsContainer.adapter = everydayAdapter

        dayItems.clear()
        daysOfWeek.forEachIndexed { index, day ->
            dayItems.add(DayItem(day, index, false, mutableListOf()))
        }

        daysAdapter = DayAdapter(
            dayItems,
            onAddTimeInterval = { dayItem, dayPosition ->
                dayItem.intervals.add(TimeInterval(9, 0, 17, 0))
                daysAdapter.notifyItemChanged(dayPosition)
            },
            onTimeClick = { interval, isStart, dayPosition, _ ->
                showTimePicker(interval, isStart, dayItems[dayPosition].intervals) { daysAdapter.notifyItemChanged(dayPosition) }
            },
            onRemoveInterval = { dayPosition, intervalPosition ->
                dayItems[dayPosition].intervals.removeAt(intervalPosition)
                daysAdapter.notifyItemChanged(dayPosition)
            },
            onDisabledClick = {
                Toast.makeText(requireContext(), "Disable everyday to set granular ranges", Toast.LENGTH_SHORT).show()
            }
        )
        daysListContainer.layoutManager = LinearLayoutManager(requireContext())
        daysListContainer.adapter = daysAdapter
    }

    private fun loadExistingSettings() {
        val config = getTimeConfig()
        switchEveryDay.isChecked = config.isEveryday
        daysAdapter.isInteractionEnabled = !config.isEveryday

        everydayIntervals.clear()
        everydayIntervals.addAll(config.everydayIntervals.map { it.copy() })
        everydayAdapter.notifyDataSetChanged()

        dayItems.forEach { dayItem ->
            val intervals = config.dailyIntervals[dayItem.dayIndex] ?: mutableListOf()
            dayItem.isActive = intervals.isNotEmpty()
            dayItem.intervals.clear()
            dayItem.intervals.addAll(intervals.map { it.copy() })
        }
        daysAdapter.notifyDataSetChanged()
    }

    private fun persistSettings() {
        val dailyIntervals = dayItems
            .filter { it.isActive }
            .associateTo(mutableMapOf()) { it.dayIndex to it.intervals.map { i -> i.copy() }.toMutableList() }
        saveTimeConfig(
            AppTimeConfig(
                isEveryday = switchEveryDay.isChecked,
                everydayIntervals = everydayIntervals.map { it.copy() }.toMutableList(),
                dailyIntervals = dailyIntervals
            )
        )
    }

    private fun showTimePicker(interval: TimeInterval, isStart: Boolean, list: MutableList<TimeInterval>, onComplete: () -> Unit) {
        val clockFormat = if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H
        val hour = if (isStart) interval.startHour else interval.endHour
        val minute = if (isStart) interval.startMinute else interval.endMinute
        val title = if (isStart) "Select Start Time" else "Select End Time"
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(clockFormat)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText(title)
            .build()

        picker.addOnPositiveButtonClickListener {
            if (isStart) {
                interval.startHour = picker.hour
                interval.startMinute = picker.minute
            } else {
                interval.endHour = picker.hour
                interval.endMinute = picker.minute
            }
            if (list.fixOvernightInterval(interval)) {
                Toast.makeText(requireContext(), "Overnight range split into two (up to midnight and from midnight)", Toast.LENGTH_LONG).show()
            }
            onComplete()
        }

        picker.show(childFragmentManager, "time_picker")
    }
}
