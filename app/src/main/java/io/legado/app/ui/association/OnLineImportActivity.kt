package io.legado.app.ui.association

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 网络一键导入
 * 格式: legado://import/{path}?src={url}
 */
class OnLineImportActivity :
    VMBaseActivity<ActivityTranslucenceBinding, OnLineImportViewModel>() {

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)
    override val viewModel by viewModels<OnLineImportViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.successLive.observe(this) {
            when (it.first) {
                "bookSource" -> showDialogFragment(
                    ImportBookSourceDialog(it.second, true)
                )
                "rssSource" -> showDialogFragment(
                    ImportRssSourceDialog(it.second, true)
                )
                "replaceRule" -> showDialogFragment(
                    ImportReplaceRuleDialog(it.second, true)
                )
                "httpTts" -> showDialogFragment(
                    ImportHttpTtsDialog(it.second, true)
                )
                "theme" -> showDialogFragment(
                    ImportThemeDialog(it.second, true)
                )
                "txtRule" -> showDialogFragment(
                    ImportTxtTocRuleDialog(it.second, true)
                )
                "dictRule" -> showDialogFragment(
                    ImportDictRuleDialog(it.second, true)
                )
            }
        }
        viewModel.errorLive.observe(this) {
            finallyDialog(getString(R.string.error), it)
        }
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        val url = data.getQueryParameter("src")
        if (url.isNullOrEmpty()) {
            finish()
            return
        }
        when (data.importPath()) {
            "/bookSource" -> showDialogFragment(
                ImportBookSourceDialog(url, true)
            )

            "/rssSource" -> showDialogFragment(
                ImportRssSourceDialog(url, true)
            )

            "/replaceRule" -> showDialogFragment(
                ImportReplaceRuleDialog(url, true)
            )
            "/textTocRule" -> showDialogFragment(
                ImportTxtTocRuleDialog(url, true)
            )
            "/httpTTS", "/httpTts", "/httptts" -> showDialogFragment(
                ImportHttpTtsDialog(url, true)
            )
            "/dictRule" -> showDialogFragment(
                ImportDictRuleDialog(url, true)
            )
            "/theme" -> showDialogFragment(
                ImportThemeDialog(url, true)
            )
            "/readConfig" -> viewModel.getBytes(url) { bytes ->
                viewModel.importReadConfig(bytes, this::finallyDialog)
            }
            "/addToBookshelf" -> showDialogFragment(
                AddToBookshelfDialog(url, true)
            )
            "/importonline" -> when (data.host) {
                "booksource" -> showDialogFragment(
                    ImportBookSourceDialog(url, true)
                )
                "rsssource" -> showDialogFragment(
                    ImportRssSourceDialog(url, true)
                )
                "replace" -> showDialogFragment(
                    ImportReplaceRuleDialog(url, true)
                )
                else -> {
                    viewModel.determineType(url, this::finallyDialog)
                }
            }
            else -> viewModel.determineType(url, this::finallyDialog)
        }
    }

    private fun Uri.importPath(): String? {
        val rawPath = path.orEmpty()
        return when {
            host == "import" && rawPath.isNotBlank() -> rawPath
            rawPath.startsWith("/import/") -> rawPath.removePrefix("/import")
            rawPath == "/import" -> "/importonline"
            else -> rawPath
        }
    }

    private fun finallyDialog(title: String, msg: String) {
        alert(title, msg) {
            okButton()
            onDismiss {
                finish()
            }
        }
    }

}
