package com.alian.assistant.presentation.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * JSON 编辑器组件
 * 支持语法高亮、格式化和验证
 */
@Composable
fun JsonEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    var showError by remember { mutableStateOf(false) }

    // 当外部 value 改变时更新内部状态
    androidx.compose.runtime.LaunchedEffect(value) {
        if (textFieldValue.text != value) {
            textFieldValue = TextFieldValue(value)
        }
    }

    Column(modifier = modifier) {
        // 工具栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    try {
                        if (textFieldValue.text.isNotBlank()) {
                            val json = JSONObject(textFieldValue.text)
                            val formatted = json.toString(2)
                            textFieldValue = TextFieldValue(formatted)
                            onValueChange(formatted)
                        }
                    } catch (e: Exception) {
                        showError = true
                    }
                },
                enabled = enabled && textFieldValue.text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FormatAlignLeft,
                    contentDescription = "格式化",
                    modifier = Modifier.height(16.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("格式化", fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    try {
                        if (textFieldValue.text.isNotBlank()) {
                            val json = JSONObject(textFieldValue.text)
                            val compressed = json.toString()
                            textFieldValue = TextFieldValue(compressed)
                            onValueChange(compressed)
                        }
                    } catch (e: Exception) {
                        showError = true
                    }
                },
                enabled = enabled && textFieldValue.text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                ),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FormatAlignRight,
                    contentDescription = "压缩",
                    modifier = Modifier.height(16.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("压缩", fontSize = 12.sp)
            }
        }

        // 编辑器区域
        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                textFieldValue = newValue
                onValueChange(newValue.text)
                showError = false
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            placeholder = {
                if (textFieldValue.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            },
            isError = isError || showError,
            enabled = enabled,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                errorBorderColor = MaterialTheme.colorScheme.error,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )

        // 错误信息
        if (isError && errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // JSON 验证错误提示
        if (showError) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "JSON 格式错误，请检查语法",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * JSON 语法高亮文本显示（只读）
 */
@Composable
fun JsonSyntaxHighlighter(
    json: String,
    modifier: Modifier = Modifier
) {
    val highlightedText = buildAnnotatedString {
        var i = 0
        while (i < json.length) {
            val char = json[i]
            when {
                char == '"' -> {
                    // 字符串值
                    val end = json.indexOf('"', i + 1)
                    if (end != -1) {
                        val stringContent = json.substring(i, end + 1)
                        withStyle(SpanStyle(color = Color(0xFF4CAF50))) {
                            append(stringContent)
                        }
                        i = end + 1
                    } else {
                        withStyle(SpanStyle(color = Color(0xFF4CAF50))) {
                            append(char)
                        }
                        i++
                    }
                }
                char == ':' -> {
                    // 冒号
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                        append(char)
                    }
                    i++
                }
                char.isWhitespace() -> {
                    // 空格和换行
                    append(char)
                    i++
                }
                char.isDigit() || char == '-' -> {
                    // 数字
                    withStyle(SpanStyle(color = Color(0xFF2196F3))) {
                        append(char)
                    }
                    i++
                }
                char in "truefalsenull" -> {
                    // 布尔值和 null
                    val keyword = when {
                        json.startsWith("true", i) -> "true"
                        json.startsWith("false", i) -> "false"
                        json.startsWith("null", i) -> "null"
                        else -> null
                    }
                    if (keyword != null) {
                        withStyle(SpanStyle(color = Color(0xFF9C27B0), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) {
                            append(keyword)
                        }
                        i += keyword.length
                    } else {
                        append(char)
                        i++
                    }
                }
                else -> {
                    // 其他字符（大括号、方括号、逗号等）
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                        append(char)
                    }
                    i++
                }
            }
        }
    }

    SelectionContainer {
        Text(
            text = highlightedText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = modifier
        )
    }
}

/**
 * JSON 验证和格式化工具函数
 */
object JsonFormatter {
    fun format(json: String): Result<String> {
        return try {
            val jsonObj = JSONObject(json)
            Result.success(jsonObj.toString(2))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun compress(json: String): Result<String> {
        return try {
            val jsonObj = JSONObject(json)
            Result.success(jsonObj.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun validate(json: String): Boolean {
        return try {
            JSONObject(json)
            true
        } catch (e: Exception) {
            false
        }
    }
}