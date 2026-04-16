package com.gomaed.photoslide.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.gomaed.photoslide.BuildConfig
import com.gomaed.photoslide.databinding.FragmentAboutBinding

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.versionHeader.text = "v${BuildConfig.VERSION_NAME}"
        binding.versionValue.text  = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        binding.rowDonate.setOnClickListener {
            openUrl("https://ko-fi.com/gomaed")
        }
        binding.rowGithub.setOnClickListener {
            openUrl("https://github.com/gomaed/PhotoSlide")
        }
        binding.rowPrivacy.setOnClickListener {
            openUrl("https://github.com/gomaed/PhotoSlide/blob/master/PRIVACY.md")
        }
        binding.rowIssue.setOnClickListener {
            openUrl("https://github.com/gomaed/PhotoSlide/issues")
        }
        binding.rowEmail.setOnClickListener {
            startActivity(Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:o0gomaed0o@gmail.com")
            })
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
