package neth.iecal.curbox.ui.fragments.main.focus

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentFocusBinding
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.BridgeServiceManager

class FocusFragment : Fragment() {

    private var _binding: FragmentFocusBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: FocusViewModel by activityViewModels()

    private var isProgrammaticScroll = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFocusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.currentRunningFocus.collect { (groupId, endTime) ->
                        if (groupId != null) {
                            binding.activeContainer.visibility = View.VISIBLE
                            binding.setupContainer.visibility = View.GONE

                            val group = viewModel.groups.value.find { it.groupId == groupId }
                            binding.tvActiveGroup.text = group!!.groupName

                            binding.btnStop.visibility = if (group.exitable) View.VISIBLE else View.GONE
                            viewModel.startTimer(endTime)
                        } else {
                            binding.activeContainer.visibility = View.GONE
                            binding.setupContainer.visibility = View.VISIBLE
                        }
                    }
                }

                launch {
                    viewModel.allSessions.collect { sessions ->
                        val runningAuto = sessions.find { it.wasAutoFocus && it.status == 0 }
                        if (runningAuto != null) {
                            val group = viewModel.autoFocusGroups.value.find { it.groupId == runningAuto.groupId }
                            if (group != null) {
                                binding.cvActiveAutoFocus.visibility = View.VISIBLE
                                binding.textHeader.visibility = View.GONE
                                val now = java.util.Calendar.getInstance()
                                val calDay = now.get(java.util.Calendar.DAY_OF_WEEK)
                                val currentDay = if (calDay == java.util.Calendar.SUNDAY) 6 else calDay - 2
                                val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
                                val intervals = group.dailyIntervals[currentDay] ?: emptyList()
                                val activeInterval = intervals.find { interval ->
                                    val start = interval.startHour * 60 + interval.startMinute
                                    val end = interval.endHour * 60 + interval.endMinute
                                    if (start <= end) currentMinutes in start until end else currentMinutes >= start || currentMinutes < end
                                }
                                if (activeInterval != null) {
                                    val startStr = String.format("%02d:%02d", activeInterval.startHour, activeInterval.startMinute)
                                    val endStr = String.format("%02d:%02d", activeInterval.endHour, activeInterval.endMinute)
                                    binding.tvAutoFocusTimeRange.text = "${group.groupName} (${startStr} - ${endStr})"
                                } else {
                                    binding.tvAutoFocusTimeRange.text = group.groupName
                                }
                                
                                binding.btnExitAutoFocus.visibility = if (group.exitable) View.VISIBLE else View.GONE
                                binding.btnExitAutoFocus.setOnClickListener {
                                    val intent = android.content.Intent(neth.iecal.curbox.blockers.FocusModeBlocker.INTENT_ACTION_EXIT_AUTO_FOCUS)
                                    intent.setPackage(requireContext().packageName)
                                    BridgeServiceManager.sendBridgedBroadcast(requireContext(), intent)
                                }
                            } else {
                                binding.cvActiveAutoFocus.visibility = View.GONE
                                binding.textHeader.visibility = View.VISIBLE
                            }
                        } else {
                            binding.cvActiveAutoFocus.visibility = View.GONE
                            binding.textHeader.visibility = View.VISIBLE
                        }

                        // Show active recurring manual focus groups
                        val now = java.util.Calendar.getInstance()
                        val calDay = now.get(java.util.Calendar.DAY_OF_WEEK)
                        val currentDay = if (calDay == java.util.Calendar.SUNDAY) 6 else calDay - 2
                        val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)

                        val activeRecurringGroups = viewModel.groups.value.filter { group ->
                            group.isRecurring && group.dailyIntervals[currentDay]?.any { interval ->
                                val start = interval.startHour * 60 + interval.startMinute
                                val end = interval.endHour * 60 + interval.endMinute
                                if (start <= end) currentMinutes in start until end
                                else currentMinutes >= start || currentMinutes < end
                            } == true
                        }

                        if (activeRecurringGroups.isNotEmpty()) {
                            binding.cvActiveRecurring.visibility = View.VISIBLE
                            val infoText = activeRecurringGroups.joinToString("\n") { group ->
                                val interval = group.dailyIntervals[currentDay]?.firstOrNull()
                                if (interval != null) {
                                    val startStr = String.format("%02d:%02d", interval.startHour, interval.startMinute)
                                    val endStr = String.format("%02d:%02d", interval.endHour, interval.endMinute)
                                    "${group.groupName} (${startStr} - ${endStr})"
                                } else {
                                    group.groupName
                                }
                            }
                            binding.tvRecurringGroupsInfo.text = infoText
                        } else {
                            binding.cvActiveRecurring.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.currentRunningTimer.collect { time ->
                        binding.tvCountdown.text = TimeTools.formatTimeInHHMM(time)
                    }
                }
            }
        }
        setupRuler()
        setupClicks()
    }
    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(requireContext())
            val runningSessions = db.focusStatsDao().getRunningSessions()
            if (runningSessions.isEmpty()) {
                val intent = android.content.Intent(neth.iecal.curbox.blockers.FocusModeBlocker.INTENT_ACTION_UNSUSPEND_ALL)
                intent.setPackage(requireContext().packageName)
                BridgeServiceManager.sendBridgedBroadcast(requireContext(), intent)
            }
        }
    }

    private fun setupClicks() {
        binding.btn15m.setOnClickListener { scrollToMinute(15) }
        binding.btn30m.setOnClickListener { scrollToMinute(30) }
        binding.btn60m.setOnClickListener { scrollToMinute(60) }
        binding.btn120m.setOnClickListener { scrollToMinute(120) }

        binding.btnGoToStats.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_holder, FocusStatsFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.btnStartConfig.setOnClickListener {
            FocusSetupBottomSheet().show(parentFragmentManager, FocusSetupBottomSheet.FRAGMENT_ID)
        }

        binding.btnStop.setOnClickListener {
            viewModel.forceStopFocus()
        }

    }


    private fun updateTime(pos:Int){
        viewModel.selectedMins = pos + 1
        binding.tvMinutes.text = viewModel.selectedMins.toString()
    }

    private fun setupRuler() {
        val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvRuler.layoutManager = layoutManager
        binding.rvRuler.adapter = RulerAdapter(240)

        val snapHelper = LinearSnapHelper()
        snapHelper.attachToRecyclerView(binding.rvRuler)

        binding.rvRuler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isProgrammaticScroll) return
                val centerView = snapHelper.findSnapView(layoutManager) ?: return
                val pos = layoutManager.getPosition(centerView)
                updateTime(pos)
            }
        })

        binding.rvRuler.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (_binding == null || binding.rvRuler.width == 0) return
                binding.rvRuler.viewTreeObserver.removeOnGlobalLayoutListener(this)

                val itemWidthPx = (20 * resources.displayMetrics.density).toInt()
                val padding = (binding.rvRuler.width / 2) - (itemWidthPx / 2)
                binding.rvRuler.setPadding(padding, 0, padding, 0)
                binding.rvRuler.clipToPadding = false
            }
        })
    }

    private fun scrollToMinute(minutes: Int, smooth: Boolean = true) {
        val targetPos = (minutes - 1).coerceAtLeast(0)
        isProgrammaticScroll = true

        if (smooth) {
            binding.rvRuler.smoothScrollToPosition(targetPos)
        } else {
            (binding.rvRuler.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(targetPos, 0)
        }

        binding.rvRuler.postDelayed({ isProgrammaticScroll = false }, 300)
        updateTime(minutes - 1)
    }




    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}