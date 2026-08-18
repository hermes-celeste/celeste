package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.Markdown as CoreMarkdown
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.m3.Markdown as MaterialMarkdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceSelected
import dev.hazydreams.hermesceleste.ui.CelestePanel
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.EmptyStreamingMarkdownFile

@Composable
internal fun RichMarkdown(
    content: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val platformUriHandler = LocalUriHandler.current
    val safeUriHandler = remember(platformUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (allowedMarkdownUri(uri)) runCatching { platformUriHandler.openUri(uri) }
            }
        }
    }

    CompositionLocalProvider(LocalUriHandler provides safeUriHandler) {
        SelectionContainer {
            if (!containsRichMarkdown(content)) {
                RawMarkdownFallback(content = content, modifier = modifier.fillMaxWidth())
            } else {
                BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
                    if (streaming) {
                        StreamingRichMarkdown(
                            content = content,
                            contentWidth = maxWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        StoredRichMarkdown(
                            content = content,
                            contentWidth = maxWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoredRichMarkdown(content: String, contentWidth: Dp, modifier: Modifier) {
    val state = rememberMarkdownState(content = content, retainState = true)
    MaterialMarkdown(
        markdownState = state,
        modifier = modifier.fillMaxWidth(),
        colors = celesteMarkdownColors(),
        typography = celesteMarkdownTypography(),
        padding = celesteMarkdownPadding(),
        dimens = celesteMarkdownDimens(contentWidth),
        components = CelesteMarkdownComponents,
        animations = markdownAnimations { this },
        loading = { RawMarkdownFallback(content, it) },
        success = { success, components, childModifier ->
            if (success.node.children.isEmpty() && content.isNotBlank()) {
                RawMarkdownFallback(content, childModifier)
            } else {
                MarkdownSuccess(success, components, childModifier)
            }
        },
        error = { RawMarkdownFallback(content, it) },
    )
}

@Composable
private fun StreamingRichMarkdown(content: String, contentWidth: Dp, modifier: Modifier) {
    var generation by remember { mutableIntStateOf(0) }
    key(generation) {
        StreamingRichMarkdownGeneration(
            content = content,
            contentWidth = contentWidth,
            modifier = modifier,
            onReset = { generation += 1 },
        )
    }
}

@Composable
private fun StreamingRichMarkdownGeneration(
    content: String,
    contentWidth: Dp,
    modifier: Modifier,
    onReset: () -> Unit,
) {
    val inspectionMode = LocalInspectionMode.current
    val stream = remember {
        EmptyStreamingMarkdownFile(GFMFlavourDescriptor()).also {
            if (inspectionMode) it.append(content)
        }
    }
    val referenceLinkHandler = remember { ReferenceLinkHandlerImpl() }
    var renderedContent by remember { mutableStateOf(if (inspectionMode) content else "") }
    var parserFailed by remember { mutableStateOf(false) }
    val currentDelta = markdownStreamDelta(renderedContent, content)

    LaunchedEffect(content, stream) {
        val delta = markdownStreamDelta(renderedContent, content)
        when {
            delta == null -> onReset()
            delta.isNotEmpty() -> runCatching { stream.append(delta) }
                .onSuccess {
                    renderedContent = content
                    parserFailed = false
                }
                .onFailure { parserFailed = true }
        }
    }

    if (currentDelta == null || parserFailed || renderedContent.isEmpty()) {
        RawMarkdownFallback(content, modifier.fillMaxWidth())
        return
    }

    CoreMarkdown(
        state = State.Success(
            node = stream,
            content = renderedContent,
            linksLookedUp = false,
            referenceLinkHandler = referenceLinkHandler,
        ),
        modifier = modifier.fillMaxWidth(),
        colors = celesteMarkdownColors(),
        typography = celesteMarkdownTypography(),
        padding = celesteMarkdownPadding(),
        dimens = celesteMarkdownDimens(contentWidth),
        components = CelesteMarkdownComponents,
        animations = markdownAnimations { this },
    )
}

@Composable
private fun RawMarkdownFallback(content: String, modifier: Modifier) {
    Text(
        text = content,
        modifier = modifier,
        color = CelesteTextPrimary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private val CelesteMarkdownComponents = markdownComponents(
    codeFence = { model ->
        if (model.node.children.size < 3) {
            RawMarkdownNodeFallback(model.content, model.node)
        } else {
            MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
                CelesteCodeBlock(code, language, style)
            }
        }
    },
    codeBlock = { model ->
        if (model.node.children.isEmpty()) {
            RawMarkdownNodeFallback(model.content, model.node)
        } else {
            MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, style ->
                CelesteCodeBlock(code, language, style)
            }
        }
    },
    paragraph = { model ->
        CelesteMarkdownParagraph(model.content, model.node, model.typography.paragraph)
    },
    orderedList = { model ->
        CelesteListSemantics(model.node) {
            MarkdownOrderedList(
                model.content,
                model.node,
                model.typography.ordered,
                model.listDepth,
            )
        }
    },
    unorderedList = { model ->
        CelesteListSemantics(model.node) {
            MarkdownBulletList(
                model.content,
                model.node,
                model.typography.bullet,
                model.listDepth,
            )
        }
    },
    table = { model ->
        CelesteMarkdownTable(model.content, model.node, model.typography.table)
    },
    image = { model ->
        RawMarkdownNodeFallback(model.content, model.node)
    },
    checkbox = { model ->
        CelesteTaskCheckbox(model.content, model.node)
    },
)

@Composable
private fun CelesteMarkdownParagraph(content: String, node: ASTNode, style: TextStyle) {
    val settings = annotatorSettings()
    val annotated = remember(content, node, style, settings) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(content = content, node = node, annotatorSettings = settings)
            pop()
        }
    }
    Text(
        text = annotated,
        modifier = Modifier.fillMaxWidth(),
        color = CelesteTextPrimary,
        style = style,
    )
}

@Composable
private fun RawMarkdownNodeFallback(content: String, node: ASTNode) {
    RawMarkdownFallback(
        content = node.getTextInNode(content).toString(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CelesteListSemantics(node: ASTNode, content: @Composable () -> Unit) {
    val itemCount = remember(node) {
        node.children.count { it.type == MarkdownElementTypes.LIST_ITEM }.coerceAtLeast(1)
    }
    Box(
        modifier = Modifier.semantics {
            isTraversalGroup = true
            collectionInfo = CollectionInfo(rowCount = itemCount, columnCount = 1)
        },
    ) {
        content()
    }
}

@Composable
private fun CelesteMarkdownTable(content: String, node: ASTNode, style: TextStyle) {
    if (node.children.none { it.type == GFMElementTypes.HEADER }) {
        RawMarkdownNodeFallback(content, node)
        return
    }
    val columnCount = remember(node) {
        node.children.firstOrNull { it.type == GFMElementTypes.HEADER }
            ?.children
            ?.count { it.type == GFMTokenTypes.CELL }
            ?.coerceAtLeast(1)
            ?: 1
    }
    val bodyRows = remember(node) { node.children.filter { it.type == GFMElementTypes.ROW } }
    val shape = RoundedCornerShape(12.dp)
    CelestePanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .semantics {
                isTraversalGroup = true
                collectionInfo = CollectionInfo(
                    rowCount = bodyRows.size + 1,
                    columnCount = columnCount,
                )
            },
        shape = shape,
        containerColor = CelesteSurfaceRaised,
    ) {
        MarkdownTable(
            content = content,
            node = node,
            style = style,
            headerBlock = { tableContent, header, width, tableStyle ->
                Box(
                    modifier = Modifier.semantics {
                        contentDescription = "Table header row"
                        collectionItemInfo = CollectionItemInfo(
                            rowIndex = 0,
                            rowSpan = 1,
                            columnIndex = 0,
                            columnSpan = columnCount,
                        )
                    },
                ) {
                    MarkdownTableHeader(tableContent, header, width, tableStyle)
                }
            },
            rowBlock = { tableContent, row, width, tableStyle ->
                val rowIndex = bodyRows.indexOf(row).coerceAtLeast(0) + 1
                Box(
                    modifier = Modifier.semantics {
                        collectionItemInfo = CollectionItemInfo(
                            rowIndex = rowIndex,
                            rowSpan = 1,
                            columnIndex = 0,
                            columnSpan = columnCount,
                        )
                    },
                ) {
                    MarkdownTableRow(tableContent, row, width, tableStyle)
                }
            },
        )
    }
}

@Composable
private fun CelesteCodeBlock(code: String, language: String?, style: TextStyle) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val label = language?.trim()?.takeIf(String::isNotEmpty)?.uppercase() ?: "CODE"
    val shape = RoundedCornerShape(12.dp)

    CelestePanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .semantics(mergeDescendants = false) {
                isTraversalGroup = true
                contentDescription = if (label == "CODE") "Code block" else "$label code block"
            },
        shape = shape,
        containerColor = CelesteSurfaceRaised,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    color = CelesteTextMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    modifier = Modifier.semantics { contentDescription = "Copy code block" },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(
                        imageVector = CopyCodeIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = CelesteTextMuted,
                    )
                }
            }
            HorizontalDivider(color = CelesteHairline)
            Text(
                text = code,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                color = CelesteTextPrimary,
                style = style,
            )
        }
    }
}

@Composable
private fun CelesteTaskCheckbox(content: String, node: ASTNode) {
    val checked = remember(content, node) {
        node.getTextInNode(content).toString().contains("[x]", ignoreCase = true)
    }
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .padding(end = 5.dp)
            .size(16.dp)
            .clip(shape)
            .background(if (checked) CelesteTextPrimary else Color.Transparent)
            .border(1.dp, if (checked) CelesteTextPrimary else CelesteHairline, shape)
            .semantics {
                contentDescription = if (checked) "Completed task" else "Incomplete task"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = TaskCheckIcon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = CelesteSurfaceRaised,
            )
        }
    }
}

@Composable
private fun celesteMarkdownColors() = markdownColor(
    text = CelesteTextPrimary,
    codeBackground = CelesteSurfaceRaised,
    inlineCodeBackground = CelesteSurfaceSelected,
    dividerColor = CelesteHairline,
    tableBackground = CelesteSurfaceRaised,
)

@Composable
private fun celesteMarkdownTypography() = markdownTypography(
    h1 = MaterialTheme.typography.titleLarge,
    h2 = MaterialTheme.typography.titleMedium,
    h3 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
    h4 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
    h5 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
    h6 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
    text = MaterialTheme.typography.bodyMedium,
    code = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    inlineCode = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    quote = MaterialTheme.typography.bodyMedium.copy(
        color = CelesteTextMuted,
        fontStyle = FontStyle.Italic,
    ),
    paragraph = MaterialTheme.typography.bodyMedium,
    ordered = MaterialTheme.typography.bodyMedium,
    bullet = MaterialTheme.typography.bodyMedium,
    list = MaterialTheme.typography.bodyMedium,
    textLink = TextLinkStyles(
        style = SpanStyle(
            color = CelesteAccent,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline,
        ),
    ),
    table = MaterialTheme.typography.bodySmall,
)

@Composable
private fun celesteMarkdownPadding() = markdownPadding(
    block = 3.dp,
    list = 2.dp,
    listItemTop = 2.dp,
    listItemBottom = 2.dp,
    listIndent = 10.dp,
    codeBlock = PaddingValues(12.dp),
    blockQuote = PaddingValues(horizontal = 12.dp),
    blockQuoteText = PaddingValues(vertical = 4.dp),
    blockQuoteBar = PaddingValues.Absolute(left = 3.dp, top = 2.dp, right = 7.dp, bottom = 2.dp),
)

@Composable
private fun celesteMarkdownDimens(contentWidth: Dp) = markdownDimens(
    dividerThickness = 1.dp,
    codeBackgroundCornerSize = 12.dp,
    blockQuoteThickness = 2.dp,
    tableMaxWidth = contentWidth,
    tableCellWidth = (contentWidth * 0.38f).coerceIn(104.dp, 148.dp),
    tableCellPadding = (contentWidth * 0.032f).coerceIn(8.dp, 12.dp),
    tableCornerSize = 12.dp,
)

private val CopyCodeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Copy code",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineTo(17f)
            horizontalLineTo(4f)
            verticalLineTo(3f)
            horizontalLineTo(16f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineTo(21f)
            curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
            horizontalLineTo(19f)
            curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
            verticalLineTo(7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineTo(19f)
            close()
        }
    }.build()
}

private val TaskCheckIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Completed",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(9f, 16.17f)
            lineTo(4.83f, 12f)
            lineTo(3.41f, 13.41f)
            lineTo(9f, 19f)
            lineTo(21f, 7f)
            lineTo(19.59f, 5.59f)
            close()
        }
    }.build()
}
