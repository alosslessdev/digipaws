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
import java.util.Locale
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import neth.iecal.curbox.utils.ViewUtils
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentFocusBinding
import androidx.core.view.isNotEmpty
import kotlin.math.abs

class FocusFragment : Fragment() {

    private var _binding: FragmentFocusBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FocusViewModel by activityViewModels()

    private var isProgrammaticScroll = false
    private var itemWidthPx = 0
    private val snapHelper = LinearSnapHelper()

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
                    viewModel.currentRunningFocus.combine(viewModel.groups) { focus, groups ->
                        focus to groups
                    }.collect { (focus, groups) ->
                        val b = _binding ?: return@collect
                        val (groupId, endTime) = focus
                        val isRunning = groupId != null
                        b.tvActiveGroup.visibility = if (isRunning) View.VISIBLE else View.GONE
                        b.btnGoToStats.visibility = if (isRunning) View.GONE else View.VISIBLE
                        b.tvSeconds.text = if (isRunning) "" else "mins"

                        if (isRunning) {
                            b.btnStartConfig.text = getString(R.string.focus_end_session)
                        } else {
                            b.btnStartConfig.text = if (groups.isEmpty()) getString(R.string.focus_create_group) else getString(R.string.focus_start)
                        }

                        if (isRunning) {
                            b.rvRuler.stopScroll()
                            snapHelper.attachToRecyclerView(null)
                            val group = groups.find { it.groupId == groupId }
                            b.tvActiveGroup.text = group?.groupName
                            b.btnStartConfig.isEnabled = group?.exitable == true
                            viewModel.startTimer(endTime)
                        } else {
                            snapHelper.attachToRecyclerView(b.rvRuler)
                            b.btnStartConfig.isEnabled = true
                            b.tvMinutes.text = viewModel.selectedMins.toString()
                            scrollToMinute(viewModel.selectedMins, smooth = false)
                        }
                    }
                }

                launch {
                    var lastTotalMinutesLeft = -1.0
                    var floatPixelAccumulator = 0.0

                    viewModel.currentRunningTimer.collect { time ->
                        val b = _binding ?: return@collect
                        val currentFocus = viewModel.currentRunningFocus.value
                        if (currentFocus.first != null && time > 0) {
                            val totalMinutesLeft = time / 60000.0
                            val minutes = (time / 60000).toInt()
                            val seconds = ((time % 60000) / 1000).toInt()

                            b.tvMinutes.text = minutes.toString()
                            b.tvSeconds.text = String.format(Locale.getDefault(), ":%02d", seconds)

                            if (b.rvRuler.width > 0 && b.rvRuler.isNotEmpty()) {
                                // Dynamically fetch the exact physical width of a rendered item
                                if (itemWidthPx > 0) {
                                    if (lastTotalMinutesLeft < 0 || abs(lastTotalMinutesLeft - totalMinutesLeft) > 1.0) {
                                        // Absolute (re)sync: place the centered tick on the remaining time.
                                        // scrollToPositionWithOffset places the item's left edge at
                                        // paddingLeft + offset, and paddingLeft already equals the
                                        // centering padding, so offset 0 centers integerPart. Shift it
                                        // left by the fractional part to land between two ticks.
                                        val fractionalPart = (totalMinutesLeft - totalMinutesLeft.toInt()).toFloat()
                                        val offset = -(fractionalPart * itemWidthPx).toInt()

                                        isProgrammaticScroll = true
                                        (b.rvRuler.layoutManager as LinearLayoutManager)
                                            .scrollToPositionWithOffset(totalMinutesLeft.toInt(), offset)
                                        isProgrammaticScroll = false // Reset instantly, onScrolled is synchronous

                                        floatPixelAccumulator = 0.0
                                    } else {
                                        // Smoothly scroll the per-tick delta to prevent layout thrashing.
                                        // Time decreases, so deltaMinutes > 0 and we scroll back (negative
                                        // dx) toward lower values, keeping the strip in sync with tvMinutes.
                                        val deltaMinutes = lastTotalMinutesLeft - totalMinutesLeft
                                        floatPixelAccumulator += deltaMinutes * itemWidthPx
                                        val pixelsToScroll = floatPixelAccumulator.toInt()

                                        if (pixelsToScroll != 0) {
                                            isProgrammaticScroll = true
                                            b.rvRuler.scrollBy(-pixelsToScroll, 0)
                                            isProgrammaticScroll = false
                                            floatPixelAccumulator -= pixelsToScroll
                                        }
                                    }
                                    lastTotalMinutesLeft = totalMinutesLeft
                                }
                            }
                        } else {
                            // Reset state when timer stops
                            lastTotalMinutesLeft = -1.0
                        }
                    }
                }
            }
        }
        setupRuler()
        setupClicks()
    }

    private fun setupClicks() {
        binding.btnGoToStats.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_holder, FocusStatsFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.btnStartConfig.setOnClickListener {
            if (viewModel.currentRunningFocus.value.first != null) {
                viewModel.forceStopFocus()
            } else {
                FocusSetupBottomSheet().show(parentFragmentManager, FocusSetupBottomSheet.FRAGMENT_ID)
            }
        }

        binding.btnHelp.setOnClickListener {
            ViewUtils.showHelpPopup(it, "Focus mode helps you stay away from distractions for a set period of time.", "https://curbox.app/docs/focus/focus-mode/")
        }
    }


    private fun updateTime(pos:Int){
        val b = _binding ?: return
        viewModel.selectedMins = pos.coerceAtLeast(1)
        b.tvMinutes.text = viewModel.selectedMins.toString()
        b.tvSeconds.text = getString(R.string.common_mins)
    }

    private fun setupRuler() {
        val bInitial = _binding ?: return
        val initialSelectedMins = viewModel.selectedMins
        isProgrammaticScroll = true

        val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        bInitial.rvRuler.layoutManager = layoutManager
        bInitial.rvRuler.adapter = RulerAdapter(240)

        bInitial.rvRuler.setOnTouchListener { _, _ ->
            viewModel.currentRunningFocus.value.first != null
        }

        snapHelper.attachToRecyclerView(bInitial.rvRuler)

        bInitial.rvRuler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isProgrammaticScroll || viewModel.currentRunningFocus.value.first != null) return
                val centerView = snapHelper.findSnapView(layoutManager) ?: return
                val pos = layoutManager.getPosition(centerView)
                updateTime(pos)
            }
        })

        bInitial.rvRuler.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val b = _binding ?: return
                if (b.rvRuler.width == 0) return
                b.rvRuler.viewTreeObserver.removeOnGlobalLayoutListener(this)

                itemWidthPx = (20 * resources.displayMetrics.density).toInt()
                val padding = (b.rvRuler.width / 2) - (itemWidthPx / 2)
                b.rvRuler.setPadding(padding, 0, padding, 0)
                b.rvRuler.clipToPadding = false

                b.rvRuler.post {
                    if (viewModel.currentRunningFocus.value.first == null) {
                        scrollToMinute(initialSelectedMins, smooth = false)
                    }
                }
            }
        })
    }

    private fun scrollToMinute(minutes: Int, smooth: Boolean = true) {
        val b = _binding ?: return
        val targetPos = minutes.coerceAtLeast(0)
        isProgrammaticScroll = true

        if (smooth) {
            b.rvRuler.smoothScrollToPosition(targetPos)
        } else {
            // paddingLeft already equals the centering padding, so offset 0 centers targetPos.
            (b.rvRuler.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(targetPos, 0)
        }

        b.rvRuler.postDelayed({ isProgrammaticScroll = false }, 300)
        if (!smooth) updateTime(minutes)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}