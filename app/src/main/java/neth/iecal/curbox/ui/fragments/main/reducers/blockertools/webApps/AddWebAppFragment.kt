package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.webApps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.textfield.TextInputEditText
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.WebApp

class AddWebAppFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "add_web_app_fragment"
    }

    private val viewModel: WebAppViewModel by activityViewModels()
    private var editingWebApp: WebApp? = null

    private lateinit var etName: TextInputEditText
    private lateinit var etUrl: TextInputEditText

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_add_web_app, container, false)

        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        etName = view.findViewById(R.id.et_web_app_name)
        etUrl = view.findViewById(R.id.et_web_app_url)

        val webAppId = requireActivity().intent.getStringExtra("web_app_id")
        if (webAppId != null) {
            editingWebApp = viewModel.webApps.value.find { it.id == webAppId }
            editingWebApp?.let {
                toolbar.title = getString(R.string.edit_web_app)
                etName.setText(it.name)
                etUrl.setText(it.url)
            }
        } else {
            toolbar.title = getString(R.string.add_web_app)
        }

        view.findViewById<View>(R.id.btn_save_web_app).setOnClickListener {
            saveWebApp()
        }

        return view
    }

    private fun saveWebApp() {
        val name = etName.text.toString().trim()
        val url = etUrl.text.toString().trim()

        if (name.isEmpty()) {
            etName.error = getString(R.string.name_required)
            return
        }
        if (url.isEmpty()) {
            etUrl.error = getString(R.string.url_required)
            return
        }

        val webApp = WebApp(
            id = editingWebApp?.id ?: java.util.UUID.randomUUID().toString(),
            name = name,
            url = url
        )

        if (editingWebApp != null) {
            viewModel.updateWebApp(webApp)
        } else {
            viewModel.addWebApp(webApp)
        }

        Toast.makeText(requireContext(), R.string.web_app_saved, Toast.LENGTH_SHORT).show()
        requireActivity().finish()
    }
}
