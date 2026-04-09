package neth.iecal.curbox.ui.fragments.usage

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.icu.util.Calendar
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity.RESULT_OK
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.toColor
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.AppUsageItemBinding

import neth.iecal.curbox.databinding.FragmentAllAppUsageBinding
import neth.iecal.curbox.ui.views.PebbleBubbleView
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerGroupsFragment
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.UsageStatsHelper
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale

class AllAppsUsageFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "all_app_usage"
    }

    private var selectedDate: Long = System.currentTimeMillis()
    private var currentDate: Long = selectedDate
    private var earliestDate: Long = selectedDate

    private var _binding: FragmentAllAppUsageBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: AllAppsUsageViewModel
    private lateinit var usageStatsHelper: UsageStatsHelper

    val selectIgnoredAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                selectedApps?.let {
                    lifecycleScope.launch(Dispatchers.IO) {
                        neth.iecal.curbox.utils.DataStoreManager(requireContext()).updateUsageTrackerIgnoredApps(it)
                    }
                    viewModel.ignoredPackages.addAll(it)
                    viewModel.reload()
                }
            }
        }

    private var csvDataToExport: String = ""

    private val createCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        requireContext().contentResolver.openOutputStream(it)?.use { stream ->
                            stream.write(csvDataToExport.toByteArray())
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.data_exported_successfully),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Log.e("ExportCSV", "Error writing file", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.failed_to_export_data),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAllAppUsageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[AllAppsUsageViewModel::class.java]
        usageStatsHelper = UsageStatsHelper(requireContext().applicationContext)

        if (!neth.iecal.curbox.utils.PermissionUtils.hasAllRequiredPermissions(requireContext())) {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", OnboardingFragment.FRAGMENT_ID)
            }
            startActivity(intent)

        }

        val adapter = AppUsageAdapter(emptyList())
        binding.appUsageRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.appUsageRecyclerView.adapter = adapter


        observeViewModel(adapter)

        binding.btnPrevWeek.setOnClickListener {
            viewModel.goToPreviousWeek()
        }
        binding.btnNextWeek.setOnClickListener {
            viewModel.goToNextWeek()
        }

        // Setup bar graph tap listener
        binding.weeklyBarGraph.setOnDaySelectedListener { dayData ->
            val index = viewModel.weeklyData.value?.indexOf(dayData) ?: return@setOnDaySelectedListener
            viewModel.selectDay(index)
            selectedDate = dayData.dateMillis
        }

        // Menu
        binding.openMenu.setOnClickListener {
            val popupMenu = PopupMenu(requireContext(), binding.openMenu)
            popupMenu.menuInflater.inflate(R.menu.usage_tracker_options, popupMenu.menu)
            lifecycleScope.launch(Dispatchers.IO) {
                val dataStore = neth.iecal.curbox.utils.DataStoreManager(requireContext())
                val keepUninstalledUsage = dataStore.settings.first().keepUninstalledUsageUntilNextDay

                withContext(Dispatchers.Main) {
                    popupMenu.menu.findItem(R.id.toggle_keep_uninstalled_usage)?.isChecked =
                        keepUninstalledUsage
                }
            }

            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.select_ignored -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val ignoredApps = neth.iecal.curbox.utils.DataStoreManager(requireContext()).settings.first().usageTrackerIgnoredApps
                            withContext(Dispatchers.Main) {
                                val intent = Intent(requireContext(), SelectAppsActivity::class.java)
                                intent.putStringArrayListExtra(
                                    "PRE_SELECTED_APPS",
                                    ArrayList(ignoredApps)
                                )
                                selectIgnoredAppsLauncher.launch(
                                    intent,
                                    ActivityOptionsCompat.makeCustomAnimation(
                                        requireContext(),
                                        R.anim.fade_in,
                                        R.anim.fade_out
                                    )
                                )
                            }
                        }
                        true
                    }

                    R.id.toggle_keep_uninstalled_usage -> {
                        val newValue = !item.isChecked
                        item.isChecked = newValue
                        lifecycleScope.launch(Dispatchers.IO) {
                            val dataStore = neth.iecal.curbox.utils.DataStoreManager(requireContext())
                            dataStore.updateKeepUninstalledUsageUntilNextDay(newValue)
                            withContext(Dispatchers.Main) {
                                viewModel.reload()
                            }
                        }
                        true
                    }

                    R.id.export_as_csv -> {

                        val dateRangePicker = MaterialDatePicker.Builder.dateRangePicker()
                            .setTitleText("Select Export Range")
                            .setSelection(
                                androidx.core.util.Pair(
                                    MaterialDatePicker.thisMonthInUtcMilliseconds(),
                                    MaterialDatePicker.todayInUtcMilliseconds()
                                )
                            )
                            .build()

                        dateRangePicker.addOnPositiveButtonClickListener { selection ->
                            val startDateMs = selection.first
                            val endDateMs = selection.second

                            val options =
                                arrayOf("Daily Breakdown (Time Series)", "Total Summary", "Both")
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Select Data Format")
                                .setSingleChoiceItems(options, 0) { dialog, which ->
                                    generateAndExportCsv(startDateMs, endDateMs, which)
                                    dialog.dismiss()
                                }
                                .show()
                        }
                        dateRangePicker.show(childFragmentManager, "EXPORT_DATE_picker")
                        true
                    }

                    R.id.add_widget_usage_tracker -> {
                        val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(requireContext())

                        if (appWidgetManager.isRequestPinAppWidgetSupported) {
                            val options = arrayOf("Screentime Stats", "Reels Stats")
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Select Widget to Pin")
                                .setItems(options) { dialog, which ->
                                    val myProvider = if (which == 0) {
                                        android.content.ComponentName(requireContext(), neth.iecal.curbox.ui.widgets.ScreentimeWidgetProvider::class.java)
                                    } else {
                                        android.content.ComponentName(requireContext(), neth.iecal.curbox.ui.widgets.ReelsWidgetProvider::class.java)
                                    }
                                    appWidgetManager.requestPinAppWidget(myProvider, null, null)
                                    dialog.dismiss()
                                }
                                .show()
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.pinning_widgets_is_not_supported_on), Toast.LENGTH_SHORT).show()
                        }
                        true
                    }

                    else -> false
                }
            }

            popupMenu.show()
        }

        // Initialize ViewModel data
        viewModel.initialize()

        lifecycleScope.launch(Dispatchers.IO) {
            findDataAvailabilityRange()
        }
    }



    private fun observeViewModel(adapter: AppUsageAdapter) {
        viewModel.weeklyData.observe(viewLifecycleOwner) { data ->
            val selectedIdx = viewModel.selectedDayIndex.value ?: 6
            binding.weeklyBarGraph.setData(data, selectedIdx)
        }

        viewModel.selectedDayIndex.observe(viewLifecycleOwner) { index ->
            binding.weeklyBarGraph.setSelectedIndex(index)
        }

        viewModel.selectedDayStats.observe(viewLifecycleOwner) { stats ->
            adapter.updateData(stats)
            updatePebbles(stats)
        }

        viewModel.totalTime.observe(viewLifecycleOwner) { totalMs ->
            binding.totalUsage.text = TimeTools.formatTimeForWidget(totalMs)
        }

        viewModel.comparisonText.observe(viewLifecycleOwner) { text ->
            binding.comparisonText.text = text
            binding.comparisonText.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.weekRangeLabel.observe(viewLifecycleOwner) { label ->
            binding.tvWeekRange.text = label
        }

        viewModel.canGoNext.observe(viewLifecycleOwner) { canGo ->
            binding.btnNextWeek.alpha = if (canGo) 1f else 0.3f
            binding.btnNextWeek.isEnabled = canGo
        }

        viewModel.dateSublabel.observe(viewLifecycleOwner) { label ->
            binding.dateSublabel.text = label
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                binding.loadingOverlay.animate().cancel()
                binding.main.animate().cancel()
                
                binding.loadingOverlay.animate().alpha(1f).setDuration(250).withStartAction {
                    binding.loadingOverlay.visibility = View.VISIBLE
                }
                binding.main.animate().alpha(0f).setDuration(250).withEndAction {
                    binding.main.visibility = View.INVISIBLE
                }
            } else {
                binding.loadingOverlay.animate().cancel()
                binding.main.animate().cancel()
                
                binding.loadingOverlay.animate().alpha(0f).setDuration(350).withEndAction {
                    binding.loadingOverlay.visibility = View.GONE
                }
                binding.main.animate().alpha(1f).setDuration(350).withStartAction {
                    binding.main.visibility = View.VISIBLE
                }
            }
        }
    }

    fun findDataAvailabilityRange() {
        val usageStatsManager = requireContext().getSystemService(UsageStatsManager::class.java)
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            0, System.currentTimeMillis()
        )

        earliestDate = stats.minOfOrNull { it.firstTimeStamp } ?: System.currentTimeMillis()
        currentDate = System.currentTimeMillis()
        selectedDate = currentDate.coerceAtLeast(earliestDate)
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            viewModel.reload()
        }
    }

    private fun updatePebbles(statsList: List<Stat>) {
        binding.pebbleLegend.removeAllViews()

        if (statsList.isEmpty()) {
            binding.pebbleView.setData(emptyList())
            return
        }

        val sortedStats = statsList.sortedByDescending { it.totalTime }
        val topApps = sortedStats.take(3)
        val maxTime = topApps.first().totalTime.toFloat()

        val colorPrimary = MaterialColors.getColor(
            requireView(),
            com.google.android.material.R.attr.colorPrimary
        )
        val colorSecondary = MaterialColors.getColor(
            requireView(),
            com.google.android.material.R.attr.colorSecondary
        )
        val colorTertiary = MaterialColors.getColor(
            requireView(),
            com.google.android.material.R.attr.colorTertiary
        )
        val colorSurface = MaterialColors.getColor(
            requireView(),
            com.google.android.material.R.attr.colorSurfaceContainerHigh
        )
        val colorOnSurface = Color.WHITE
        val colorSurfaceVariant = MaterialColors.getColor(
            requireView(),
            com.google.android.material.R.attr.colorSurfaceVariant
        )
        val fallbackColors = listOf(colorPrimary, colorSecondary, colorTertiary)

        val pebbleDataList = mutableListOf<PebbleBubbleView.PebbleData>()
        val legendColors = mutableListOf<Int>()

        topApps.forEachIndexed { index, stat ->
            val weight = if (maxTime > 0) stat.totalTime.toFloat() / maxTime else 0.5f

            val metadata = viewModel.getAppMetadata(stat.packageName)
            val blendedColor = metadata.icon?.let { icon ->
                val dominantColor = neth.iecal.curbox.utils.ColorUtils.getDominantColor(icon)
                MaterialColors.layer(colorOnSurface, dominantColor, 0.45f)
            } ?: fallbackColors.getOrElse(index) { colorSurfaceVariant }

            val label = metadata.label.toString()

            pebbleDataList.add(PebbleBubbleView.PebbleData(blendedColor, weight, label))
            legendColors.add(blendedColor)
        }

        binding.pebbleView.setData(pebbleDataList)

        // Build legend: colored dot + app name
        val density = resources.displayMetrics.density

        topApps.forEachIndexed { index, stat ->
            val itemLayout = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            }

            // Colored dot
            val dot = android.view.View(requireContext()).apply {
                val size = (7 * density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
                val drawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(legendColors.getOrElse(index) { colorSurfaceVariant })
                }
                background = drawable
            }
            itemLayout.addView(dot)

            // App name
            val metadata = viewModel.getAppMetadata(stat.packageName)
            val nameView = android.widget.TextView(requireContext()).apply {
                text = metadata.label
                setTextColor(MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant))
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (5 * density).toInt()
                }
            }
            itemLayout.addView(nameView)

            binding.pebbleLegend.addView(itemLayout)
        }
    }

    private fun generateAndExportCsv(startMs: Long, endMs: Long, mode: Int) {
        Toast.makeText(requireContext(), getString(R.string.generating_analysis_csv), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            val header =
                "Date,App Name,Package Name,Category,Duration (ms),Duration (mins),Is System App,Install Date,Last Update,Start Times\n"

            fun processStatsForRange(start: Long, end: Long, dateLabel: String): String {
                val rangeSb = StringBuilder()
                val statsMap = usageStatsHelper.getForegroundStatsByTimestamps(start, end)

                statsMap.forEach { it ->
                    if (it.totalTime > 0) {
                        val metadata = viewModel.getAppMetadata(it.packageName)
                        val appName = metadata.label.toString().replace(",", " ")
                        val category = when (metadata.category) {
                            "GAME" -> "Game"
                            "SOCIAL NETWORKING" -> "Social"
                            "PRODUCTIVITY" -> "Productivity"
                            "VIDEO" -> "Video"
                            "AUDIO" -> "Audio"
                            else -> "Other"
                        }
                        val isSystem = if (metadata.isSystemApp) "Yes" else "No"
                        val installDate = metadata.installDate
                        val lastUpdate = metadata.lastUpdate

                        val minutes = it.totalTime / 1000 / 60

                        rangeSb.append("$dateLabel,$appName,${it.packageName},$category,${it.totalTime},$minutes,$isSystem,$installDate,$lastUpdate\n,${it.startTimes}\n")
                    }
                }
                return rangeSb.toString()
            }

            if (mode == 0 || mode == 2) {
                sb.append("--- DAILY BREAKDOWN ---\n")
                sb.append(header)

                val startInstant =
                    Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDate()
                val endInstant =
                    Instant.ofEpochMilli(endMs).atZone(ZoneId.systemDefault()).toLocalDate()

                var current = startInstant
                while (!current.isAfter(endInstant)) {
                    val dayStart =
                        current.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val dayEnd = current.atTime(23, 59, 59).atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli()

                    sb.append(processStatsForRange(dayStart, dayEnd, current.toString()))
                    current = current.plusDays(1)
                }
                sb.append("\n")
            }

            if (mode == 1 || mode == 2) {
                sb.append(
                    "--- TOTAL SUMMARY (${dateFormat.format(Date(startMs))} to ${
                        dateFormat.format(
                            Date(endMs)
                        )
                    }) ---\n"
                )
                sb.append(header)
                sb.append(processStatsForRange(startMs, endMs, "TOTAL RANGE"))
            }

            csvDataToExport = sb.toString()

            withContext(Dispatchers.Main) {
                val name = "UsageData_${dateFormat.format(Date(startMs))}.csv"
                createCsvLauncher.launch(name)
            }
        }
    }

    private fun resizeIcon(icon: Drawable, width: Int, height: Int): Drawable {
        val bitmap = if (icon is BitmapDrawable) {
            icon.bitmap
        } else {
            val bitmap = Bitmap.createBitmap(
                icon.intrinsicWidth,
                icon.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            icon.setBounds(0, 0, canvas.width, canvas.height)
            icon.draw(canvas)
            bitmap
        }

        val density = Resources.getSystem().displayMetrics.density
        val targetWidth = (width * density).toInt()
        val targetHeight = (height * density).toInt()

        val scaledBitmap = Bitmap.createScaledBitmap(
            bitmap,
            targetWidth,
            targetHeight,
            true
        )

        return BitmapDrawable(Resources.getSystem(), scaledBitmap)
    }

    inner class AppUsageViewHolder(private val binding: AppUsageItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stats: Stat) {
            val metadata = viewModel.getAppMetadata(stats.packageName)
            binding.appIcon.setImageDrawable(metadata.icon ?: ContextCompat.getDrawable(requireContext(), R.drawable.baseline_warning_24))
            binding.root.setOnClickListener {
                activity?.supportFragmentManager?.beginTransaction()
                    ?.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                    ?.replace(R.id.fragment_holder, AppUsageBreakdown(stats))
                    ?.addToBackStack(null)
                    ?.commit()
            }
            binding.root.setOnLongClickListener {

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Add to ignored packages?")
                    .setMessage("This action will cause the tracker to not display any stats from this app.")
                    .setCancelable(true)
                    .setPositiveButton("Okay") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val dataStore = neth.iecal.curbox.utils.DataStoreManager(requireContext())
                            val ignoredAppsSP = dataStore.settings.first().usageTrackerIgnoredApps.toMutableList()
                            if (!ignoredAppsSP.contains(stats.packageName)) {
                                ignoredAppsSP.add(stats.packageName)
                                dataStore.updateUsageTrackerIgnoredApps(ignoredAppsSP)
                                withContext(Dispatchers.Main) {
                                    viewModel.ignoredPackages.addAll(ignoredAppsSP)
                                    viewModel.reload()
                                }
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }

            binding.appName.text = metadata.label
            binding.appUsage.text = TimeTools.formatTimeForWidget(stats.totalTime)
            binding.appCategory.text = metadata.category
        }
    }

    inner class AppUsageAdapter(
        private var appUsageStats: List<Stat>
    ) : RecyclerView.Adapter<AppUsageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppUsageViewHolder {
            val binding =
                AppUsageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return AppUsageViewHolder(binding)
        }

        override fun onBindViewHolder(holder: AppUsageViewHolder, position: Int) {
            holder.bind(appUsageStats[position])
        }

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newAppUsageStats: List<Stat>) {
            appUsageStats = newAppUsageStats
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = appUsageStats.size
    }



    class Stat(
        val packageName: String,
        val totalTime: Long,
        val startTimes: List<ZonedDateTime>,
        val hourlyUsage: LongArray = LongArray(24),
        val snapshotLabel: String? = null,
        val snapshotCategory: String? = null
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
