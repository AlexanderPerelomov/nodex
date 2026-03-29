package com.example.nodex.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.nodex.R
import com.example.nodex.domain.service.ipfs.IPFSException
import com.example.nodex.ui.theme.CircularCornerRadius
import com.example.nodex.ui.theme.LargePadding
import com.example.nodex.ui.theme.MediumPadding
import com.example.nodex.ui.theme.Typography
import com.example.nodex.ui.theme.XSmallPadding
import org.koin.androidx.compose.koinViewModel

@Composable
fun MainScreen(
    paddingValues: PaddingValues,
    viewModel: MainViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .padding(paddingValues)
            .padding(LargePadding)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(LargePadding)
    ) {
        EditText(
            value = state.multiAddress,
            onValueChange = {
                viewModel.dispatch(
                    MainViewModel.OnAddressChanged(it)
                )
            },
            label = stringResource(R.string.scr_main_lbl_address),
            enabled = state.inputEnabled,
        )
        EditText(
            value = state.cid,
            onValueChange = {
                viewModel.dispatch(
                    MainViewModel.OnCIDChanged(it)
                )
            },
            label = stringResource(R.string.scr_main_lbl_cid),
            enabled = state.inputEnabled,
        )
        EditText(
            value = state.pollingInterval,
            onValueChange = {
                viewModel.dispatch(
                    MainViewModel.OnPollingIntervalChanged(it)
                )
            },
            label = stringResource(R.string.scr_main_lbl_polling_interval),
            enabled = state.inputEnabled,
            keyBoardType = KeyboardType.Number,
        )

        Row(
            modifier = Modifier
                .padding(horizontal = LargePadding),
            horizontalArrangement = Arrangement.spacedBy(MediumPadding),
            verticalAlignment = Alignment.Bottom
        ) {
            val value by animateIntAsState(state.latency ?: 0)
            val latency = if (state.latency == null) {
                "NaN"
            } else {
                value.toString()
            }
            Text(
                text = latency,
                style = Typography.headlineLarge
            )
            Text(text = stringResource(R.string.scr_main_lbl_ms))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(
                    color = Color.White,
                    shape = RoundedCornerShape(LargePadding)
                )
                .padding(LargePadding),
            verticalArrangement = Arrangement.spacedBy(MediumPadding)
        ) {
            Text(text = stringResource(R.string.scr_main_lbl_response))
            Crossfade(
                targetState = state.content
            ) { state ->
                when {
                    state?.isSuccess == true || state == null -> {
                        Text(
                            text = state?.getOrNull().orEmpty(),
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            style = Typography.bodyLarge,
                        )
                    }
                    else -> {
                        Text(
                            text = state.exceptionOrNull()?.getErrorMessage().orEmpty(),
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            color = Color.Red,
                            style = Typography.bodyLarge,
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            {
                if (!state.isProgress) {
                    focusManager.clearFocus()
                    viewModel.dispatch(MainViewModel.ActionButton)
                }
            },
            modifier = Modifier
                .align(Alignment.CenterHorizontally),
            shape = RoundedCornerShape(CircularCornerRadius),
        ) {
            Crossfade(
                state.isProgress
            ) { isProgress ->
                if (isProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ProgressSize),
                        strokeWidth = XSmallPadding,
                    )
                } else {
                    Icon(
                        painter = painterResource(state.actionIconRes),
                        contentDescription = null
                    )
                }
            }
        }
    }
}

@Composable
private fun EditText(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyBoardType: KeyboardType = KeyboardType.Unspecified,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(XSmallPadding),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = LargePadding),
            style = Typography.labelSmall,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth()
                .heightIn(EditTextHeight),
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyBoardType,
                imeAction = ImeAction.Done,
            ),
            textStyle = Typography.bodySmall,
            shape = RoundedCornerShape(LargePadding),
        )
    }
}

@Composable
private fun Throwable.getErrorMessage(): String {
    return when (this) {
        is IPFSException.UnreachableException -> stringResource(R.string.scr_main_lbl_node_unreachable)
        is IPFSException.WrongCIDException -> stringResource(R.string.scr_main_lbl_wrong_cid)
        else -> stringResource(R.string.scr_main_lbl_something_wrong)
    }
}

private val EditTextHeight = 48.dp
private val ProgressSize = 24.dp
