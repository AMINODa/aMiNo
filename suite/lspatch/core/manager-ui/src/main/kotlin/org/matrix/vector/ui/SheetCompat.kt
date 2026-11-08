package org.matrix.vector.ui

import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * aMiNo v2.x — AGP 8.11 compatibility shims (build toolchain ONLY, zero UI change).
 *
 * The vendored manager-ui sources use two material3 1.5.0-alpha19+ APIs:
 * `rememberBottomSheetState(initialValue=...)` and the universal-content [ListItem] overload
 * (trailing content lambda, no required headlineContent). That alpha line's AAR metadata
 * demands Android Gradle plugin 9.1+ (verified by reading each AAR's
 * aar-metadata.properties: alpha18 == 8.6.0, alpha19+ == 9.1.0), and aMiNo builds on
 * AGP 8.11.1 — so the material3 family is pinned to 1.5.0-alpha18 (root build.gradle force
 * list), which predates both.
 *
 * These shims restore the exact call shapes the sources use on top of alpha18's stable APIs.
 * Every affected call site differs from upstream ONLY in its import line; the composed rows
 * and sheets carry identical content, colours and behaviour.
 */

@Composable
fun rememberBottomSheetState(initialValue: SheetValue = SheetValue.Hidden): SheetState =
    @Suppress("DEPRECATION")
    rememberModalBottomSheetState(skipPartiallyExpanded = false)

@Composable
fun ListItem(
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.ListItem(
        headlineContent = content,
        modifier = modifier,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = colors,
    )
}
