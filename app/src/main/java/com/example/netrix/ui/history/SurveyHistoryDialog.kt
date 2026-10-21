package com.example.netrix.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netrix.service.export.CsvExporter
import com.example.netrix.ui.NetrixViewModel
import com.example.netrix.ui.components.TerminalButton
import com.example.ui.theme.NetrixBorder
import com.example.ui.theme.NetrixGreen
import com.example.ui.theme.NetrixPrimary
import com.example.ui.theme.NetrixRed
import com.example.ui.theme.NetrixSurface
import com.example.ui.theme.NetrixSurfaceSubtle
import com.example.ui.theme.NetrixText
import com.example.ui.theme.NetrixTextDim
import com.example.ui.theme.NetrixVoid
import com.example.ui.theme.NetrixYellow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SurveyHistoryDialog(
    viewModel: NetrixViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sessions by viewModel.allSessions.collectAsState()
    val scope = rememberCoroutineScope()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TerminalButton(
                text = "CLOSE",
                onClick = onDismiss,
                modifier = Modifier.height(34.dp)
            )
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SURVEY SESSION HISTORY",
                    color = NetrixPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${sessions.size} sessions",
                    color = NetrixTextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .border(1.dp, NetrixBorder)
                    .background(NetrixVoid)
                    .padding(4.dp)
            ) {
                if (sessions.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "[NO STORED SURVEY SESSIONS]",
                            color = NetrixTextDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Start and finalize a survey in ARM to record persistent RF history.",
                            color = NetrixTextDim,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(sessions) { session ->
                            val statusColor = when (session.status) {
                                "FINALIZED" -> NetrixGreen
                                "ACTIVE" -> NetrixPrimary
                                else -> NetrixYellow
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(NetrixSurface)
                                    .border(1.dp, NetrixBorder)
                                    .padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = session.name,
                                        color = NetrixText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "[${session.status}]",
                                        color = statusColor,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Spacer(modifier = Modifier.height(3.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = dateFormat.format(Date(session.startTime)),
                                        color = NetrixTextDim,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "Samples: ${session.totalSamples} | Dead: ${session.deadZonesCount}",
                                        color = NetrixPrimary,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    TerminalButton(
                                        text = "EXPORT CSV",
                                        onClick = {
                                            scope.launch {
                                                val samples = viewModel.dao.getSamplesListForSession(session.id)
                                                val csv = CsvExporter.exportSpatialSamplesToCsv(session, samples)
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("SURVEY_CSV", csv))
                                                Toast.makeText(context, "Session CSV copied (${samples.size} points)", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(28.dp)
                                    )
                                    TerminalButton(
                                        text = "DELETE",
                                        onClick = {
                                            scope.launch {
                                                viewModel.dao.deleteSession(session.id)
                                                Toast.makeText(context, "Session deleted", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.height(28.dp),
                                        color = NetrixRed,
                                        textColor = NetrixRed
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = NetrixSurface,
        shape = RectangleShape
    )
}
