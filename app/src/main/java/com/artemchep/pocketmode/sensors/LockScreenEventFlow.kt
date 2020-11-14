package com.artemchep.pocketmode.sensors

import androidx.lifecycle.LiveData
import androidx.lifecycle.asFlow
import com.artemchep.pocketmode.Cfg
import com.artemchep.pocketmode.keyguardUnlockedDelay
import com.artemchep.pocketmode.models.Keyguard
import com.artemchep.pocketmode.models.PhoneCall
import com.artemchep.pocketmode.models.Proximity
import com.artemchep.pocketmode.models.Screen
import com.artemchep.pocketmode.models.events.BeforeLockScreen
import com.artemchep.pocketmode.models.events.Idle
import com.artemchep.pocketmode.models.events.LockScreenEvent
import com.artemchep.pocketmode.models.events.OnLockScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/**
 * @author Artem Chepurnoy
 */
@Suppress("FunctionName")
fun LockScreenEventFlow(
    proximityLiveData: LiveData<Proximity?>,
    screenLiveData: LiveData<Screen?>,
    phoneCallLiveData: LiveData<PhoneCall>,
    keyguardLiveData: LiveData<Keyguard>
): Flow<LockScreenEvent> {
    val proximityFlow = proximityLiveData.asFlow().filterNotNull().distinctUntilChanged()
    val screenFlow = screenLiveData.asFlow().filterNotNull().distinctUntilChanged()
    val phoneCallFlow = phoneCallLiveData.asFlow().distinctUntilChanged()
    val keyguardFlow = keyguardLiveData.asFlow().distinctUntilChanged()
    return screenFlow
        // If the screen is on -> subscribe to the
        // keyguard flow.
        .flatMapLatest { screen ->
            when (screen) {
                is Screen.On -> combine(
                    keyguardFlow,
                    phoneCallFlow,
                ) { keyguard, phoneCall ->
                    when {
                        // Disable the pocket mode while the call
                        // is ongoing.
                        phoneCall is PhoneCall.Ongoing -> flowOf(false)
                        // Stay subscribed while the user is on the
                        // keyguard.
                        keyguard is Keyguard.Locked -> flowOf(true)
                        keyguard is Keyguard.Unlocked -> flow {
                            emit(true)
                            delay(Cfg.keyguardUnlockedDelay)
                            emit(false)
                        }
                        else -> throw IllegalStateException()
                    }
                }
                    .flatMapLatest { it }
                    .flatMapLatest { isActive ->
                        if (isActive) {
                            proximityFlow
                                .flatMapLatest { proximity ->
                                    when (proximity) {
                                        is Proximity.Far -> flowOf(Idle)
                                        // When the sensor gets covered, wait for a bit and if it
                                        // does not get uncovered -> send the lock screen event.
                                        is Proximity.Near -> flow {
                                            emit(BeforeLockScreen)
                                            delay(Cfg.lockScreenDelay)
                                            emit(OnLockScreen)
                                        }
                                    }
                                }
                        } else flowOf(Idle)
                    }
                is Screen.Off -> flowOf(Idle)
            }
        }
}
