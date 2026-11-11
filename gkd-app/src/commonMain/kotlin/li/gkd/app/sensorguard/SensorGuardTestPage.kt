package li.gkd.app.sensorguard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import li.gkd.app.resources.*
import li.gkd.app.ui.MainViewModel
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkScaffold
import li.gkd.app.ui.component.GkSettingItem
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.navigation.PrivilegeServiceRoute
import li.gkd.app.ui.platform.privilegeAvailable
import li.gkd.app.ui.style.itemHorizontalPadding
import li.gkd.app.ui.style.itemVerticalPadding
import li.gkd.app.ui.style.titleItemPadding
import org.jetbrains.compose.resources.stringResource

@Composable
fun SensorGuardTestPage() {
    val mainVm = MainViewModel.requireCurrent()
    val vm = viewModel { SensorGuardTestViewModel() }
    val freezing by vm.freezing.collectAsStateWithLifecycle()
    val remaining by vm.remainingSeconds.collectAsStateWithLifecycle()
    val privilegeConnected = privilegeAvailable()

    // Only restore when leaving this page (pop / replace), not when switching to another app
    // to observe Sensor Box — countdown must keep running in the background.
    DisposableEffect(Unit) {
        onDispose { vm.restoreOnLeave() }
    }

    GkScaffold(
        topBar = {
            GkTopAppBar(
                navigationIcon = {
                    GkIconButton(
                        imageVector = GkIcons.ArrowBack,
                        onClick = mainVm.navigator::pop,
                    )
                },
                title = { Text(text = stringResource(Res.string.sensor_guard_test_title)) },
            )
        },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(Res.string.sensor_guard_test_section),
                modifier = Modifier.titleItemPadding(showTop = false),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(Res.string.sensor_guard_test_description),
                modifier =
                    Modifier.padding(
                        horizontal = itemHorizontalPadding,
                        vertical = itemVerticalPadding,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GkSettingItem(
                title = stringResource(Res.string.privilege_service),
                subtitle =
                    stringResource(
                        if (privilegeConnected) Res.string.privilege_service_connected
                        else Res.string.privilege_service_disconnected,
                    ),
                onClick = { mainVm.navigator.navigate(PrivilegeServiceRoute) },
            )
            TextButton(
                onClick = { mainVm.navigator.navigate(PrivilegeServiceRoute) },
                modifier = Modifier.padding(horizontal = itemHorizontalPadding),
            ) {
                Text(stringResource(Res.string.privilege_service_open))
            }
            Text(
                text = stringResource(Res.string.sensor_guard_test_actions),
                modifier = Modifier.titleItemPadding(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            GkSettingItem(
                title = stringResource(Res.string.sensor_guard_test_freeze_5s),
                subtitle =
                    when {
                        freezing && remaining != null ->
                            stringResource(
                                Res.string.sensor_guard_test_remaining,
                                remaining.toString(),
                            )
                        freezing -> stringResource(Res.string.sensor_guard_test_freezing)
                        !privilegeConnected ->
                            stringResource(Res.string.sensor_guard_test_need_privilege)
                        else -> null
                    },
                onClick =
                    if (!freezing && privilegeConnected) vm::startFreezeTest else null,
            )
            GkSettingItem(
                title = stringResource(Res.string.sensor_guard_test_restore_now),
                onClick = if (privilegeConnected) vm::restoreNow else null,
            )
            GkPageBottomSpace()
        }
    }
}
