package net.rpcs3.ui.debug

import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcs3.R
import net.rpcs3.benchmark.BenchmarkPrefs
import net.rpcs3.provider.AppDataDocumentProvider
import net.rpcs3.ui.components.PaneNavRow
import net.rpcs3.ui.components.PaneSectionTitle
import net.rpcs3.ui.components.SettingSwitch
import net.rpcs3.ui.theme.Dims
import net.rpcs3.ui.theme.SettingsStyle

@Composable
fun BenchmarkPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(BenchmarkPrefs.isEnabled(context)) }
    var latestName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        latestName = withContext(Dispatchers.IO) { BenchmarkPrefs.latest()?.name }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        PaneSectionTitle(stringResource(R.string.debug_tab_benchmark))

        SettingSwitch(
            label = stringResource(R.string.benchmark_record),
            subtitle = stringResource(R.string.benchmark_record_hint),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                BenchmarkPrefs.setEnabled(context, it)
            }
        )

        Spacer(Modifier.height(Dims.RowSpacing))

        Text(
            text = stringResource(R.string.benchmark_how),
            style = MaterialTheme.typography.bodySmall,
            color = SettingsStyle.TextDim
        )

        Spacer(Modifier.height(Dims.RowSpacing))

        PaneNavRow(
            title = stringResource(R.string.benchmark_share_last),
            description = latestName?.let { stringResource(R.string.benchmark_last, it) }
                ?: stringResource(R.string.benchmark_none),
            icon = Icons.Outlined.Share,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val name = latestName
                if (name == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.benchmark_none),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@PaneNavRow
                }

                val uri = DocumentsContract.buildDocumentUri(
                    AppDataDocumentProvider.authorityOf(context),
                    "${AppDataDocumentProvider.ROOT_ID}/${BenchmarkPrefs.FOLDER}/$name"
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    setDataAndType(uri, "text/csv")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_STREAM, uri)
                }
                context.startActivity(
                    Intent.createChooser(
                        intent,
                        context.getString(R.string.benchmark_share_chooser)
                    )
                )
            }
        )
    }
}
