package com.cinnamon.vantracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinnamon.vantracker.data.Repo
import com.cinnamon.vantracker.data.Trip
import com.cinnamon.vantracker.data.UserProfile
import com.cinnamon.vantracker.data.normDept
import com.cinnamon.vantracker.tracking.TripCalc

/** Trips this person may confirm: their own department's, or every one for GM / OM / admins. */
fun tripsToJustify(trips: List<Trip>, me: UserProfile) = trips.filter {
    me.isManager || me.isAdmin || (me.isDeptLead && it.justifyDepartment == normDept(me.department))
}

/** Home-screen banner while urgent trips wait for this HOD / executive. */
@Composable
fun JustifyBanner(s: Session) {
    if (!(s.me.isDeptLead || s.canApprove)) return
    val pending by remember { Repo.justifyFlow() }.collectAsState(initial = emptyList())
    val mine = tripsToJustify(pending, s.me)
    if (mine.isEmpty()) return
    Banner(
        if (mine.size == 1) "🚐 An urgent trip by ${mine[0].driverName} needs your confirmation – tap to review."
        else "🚐 ${mine.size} urgent trips need your confirmation – tap to review.",
        WarnOrange,
    ) { s.nav.go(Screen.Justify) }
}

/**
 * Urgent trips a driver started without a request: the passengers' HOD / executive confirms
 * the trip was for their department (it then counts as a normal trip) or disputes it.
 */
@Composable
fun JustifyScreen(s: Session) {
    val pending by remember { Repo.justifyFlow() }.collectAsState(initial = emptyList())
    val mine = tripsToJustify(pending, s.me)
    Scaffold(topBar = { AppBar("Confirm urgent trips", onBack = { s.nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Text(
                    "Drivers sometimes take staff on an urgent trip before a request can be made. Confirm the trips your " +
                        "department used – they then appear in the trip log and reports like any approved trip.",
                    modifier = Modifier.padding(16.dp), color = TextSecondary, fontSize = 14.sp,
                )
            }
            if (mine.isEmpty()) item { Empty("Nothing to confirm 👍") }
            items(mine, key = { it.id }) { t ->
                var comment by remember(t.id) { mutableStateOf("") }
                TripCard(t, s, onClick = { s.nav.go(Screen.TripDetail(t.id)) }) {
                    InfoRow("Driver’s reason", t.reason)
                    InfoRow("Department", t.justifyDepartment)
                    if (t.passengerNames.isNotBlank()) InfoRow("Passenger(s)", t.passengerNames)
                    t.endTime?.let { InfoRow("Ended", TripCalc.dateTime(it)) }
                    OutlinedTextField(comment, { comment = it }, label = { Text("Comment (optional)") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { Repo.justifyTrip(t, s.me, ok = true, comment = comment.trim()) },
                            colors = ButtonDefaults.buttonColors(containerColor = OkGreen),
                        ) { Text("Confirm – justified") }
                        OutlinedButton(onClick = { Repo.justifyTrip(t, s.me, ok = false, comment = comment.trim()) }) {
                            Text("Not ours", color = DangerRed)
                        }
                    }
                }
            }
        }
    }
}
