package com.indigo.mobileobservatory.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.util.FileLogger

@Composable
internal fun Lp750Credit(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val profileUrl = stringResource(R.string.lp750_author_url)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.lp750_credit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            profileUrl,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.lp750_author_link)
                ) {
                    try {
                        uriHandler.openUri(profileUrl)
                    } catch (error: IllegalArgumentException) {
                        FileLogger.e("LP750", "Failed to open author profile: ${error.message}")
                        Toast.makeText(context, R.string.lp750_link_failed, Toast.LENGTH_LONG).show()
                    }
                }
                .wrapContentHeight(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline
        )
    }
}
