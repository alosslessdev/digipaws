package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.webApps

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.WebApp
import neth.iecal.curbox.ui.activity.FragmentActivity

class WebAppsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "web_apps_fragment"
    }

    private lateinit var rvWebApps: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var fabAddWebApp: FloatingActionButton
    private lateinit var toolbar: MaterialToolbar

    private val viewModel: WebAppViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_web_apps, container, false)

        rvWebApps = view.findViewById(R.id.rv_web_apps)
        tvEmptyState = view.findViewById(R.id.tv_empty_state)
        fabAddWebApp = view.findViewById(R.id.fab_add_web_app)
        toolbar = view.findViewById(R.id.toolbar)

        toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        fabAddWebApp.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", AddWebAppFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        rvWebApps.layoutManager = LinearLayoutManager(requireContext())

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.webApps.collectLatest { webApps ->
                    if (webApps.isEmpty()) {
                        tvEmptyState.visibility = View.VISIBLE
                        rvWebApps.visibility = View.GONE
                    } else {
                        tvEmptyState.visibility = View.GONE
                        rvWebApps.visibility = View.VISIBLE
                        rvWebApps.adapter = WebAppAdapter(webApps) { webApp, action ->
                            when (action) {
                                "delete" -> viewModel.removeWebApp(webApp)
                                "edit" -> {
                                    val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                                        putExtra("fragment", AddWebAppFragment.FRAGMENT_ID)
                                        putExtra("web_app_id", webApp.id)
                                    }
                                    startActivity(intent)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private class WebAppAdapter(
        private var webApps: List<WebApp>,
        private val onAction: (WebApp, String) -> Unit
    ) : RecyclerView.Adapter<WebAppAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_web_app_name)
            val tvUrl: TextView = view.findViewById(R.id.tv_web_app_url)
            val btnDelete: View = view.findViewById(R.id.btn_delete_web_app)
            val btnEdit: View = view.findViewById(R.id.btn_edit_web_app)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_web_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val webApp = webApps[position]
            holder.tvName.text = webApp.name
            holder.tvUrl.text = webApp.url
            holder.btnDelete.setOnClickListener { onAction(webApp, "delete") }
            holder.btnEdit.setOnClickListener { onAction(webApp, "edit") }
        }

        override fun getItemCount() = webApps.size

        fun updateData(newWebApps: List<WebApp>) {
            webApps = newWebApps
            notifyDataSetChanged()
        }
    }
}
