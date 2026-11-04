package com.valhalla.core.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceStateMachineTest {

    @Test
    fun `默认从未连接状态开始`() {
        val machine = DeviceStateMachine()
        assertEquals(DeviceState.DISCONNECTED, machine.state)
        assertFalse(machine.isConnected)
        assertFalse(machine.isReady)
    }

    @Test
    fun `完整生命周期迁移`() {
        val machine = DeviceStateMachine()

        assertTrue(machine.handle(DeviceEvent.ConnectRequested) is TransitionResult.Accepted)
        assertEquals(DeviceState.CONNECTING, machine.state)

        machine.handle(DeviceEvent.LinkEstablished)
        assertEquals(DeviceState.CONNECTED, machine.state)
        assertTrue(machine.isConnected)

        machine.handle(DeviceEvent.AuthenticationStarted)
        assertEquals(DeviceState.AUTHENTICATING, machine.state)

        machine.handle(DeviceEvent.AuthSucceeded)
        assertEquals(DeviceState.READY, machine.state)
        assertTrue(machine.isReady)
        assertEquals(4, machine.transitions().size)
    }

    @Test
    fun `鉴权失败进入异常并可重置`() {
        val machine = DeviceStateMachine(DeviceState.AUTHENTICATING)

        val result = machine.handle(DeviceEvent.AuthFailed("token 过期"))
        assertTrue(result is TransitionResult.Accepted)
        assertEquals(DeviceState.FAULT, machine.state)

        machine.handle(DeviceEvent.ResetRequested)
        assertEquals(DeviceState.DISCONNECTED, machine.state)
    }

    @Test
    fun `链路丢失从就绪回落到未连接`() {
        val machine = DeviceStateMachine(DeviceState.READY)
        machine.handle(DeviceEvent.LinkLost)
        assertEquals(DeviceState.DISCONNECTED, machine.state)
    }

    @Test
    fun `主动断开流程`() {
        val machine = DeviceStateMachine(DeviceState.READY)

        machine.handle(DeviceEvent.DisconnectRequested)
        assertEquals(DeviceState.DISCONNECTING, machine.state)

        machine.handle(DeviceEvent.Disconnected)
        assertEquals(DeviceState.DISCONNECTED, machine.state)
    }

    @Test
    fun `非法事件被拒绝且状态保持不变`() {
        val machine = DeviceStateMachine()

        val result = machine.handle(DeviceEvent.LinkEstablished)
        assertTrue(result is TransitionResult.Rejected)
        assertEquals(DeviceState.DISCONNECTED, machine.state)

        val authResult = DeviceStateMachine(DeviceState.CONNECTED).handle(DeviceEvent.AuthSucceeded)
        assertTrue(authResult is TransitionResult.Rejected)
    }

    @Test
    fun `重复事件被忽略`() {
        val machine = DeviceStateMachine(DeviceState.CONNECTING)
        val duplicateConnect = machine.handle(DeviceEvent.ConnectRequested)
        assertTrue(duplicateConnect is TransitionResult.Ignored)

        val ready = DeviceStateMachine(DeviceState.READY)
        assertTrue(ready.handle(DeviceEvent.AuthSucceeded) is TransitionResult.Ignored)
        assertTrue(ready.handle(DeviceEvent.AuthenticationStarted) is TransitionResult.Ignored)
        assertTrue(ready.handle(DeviceEvent.DisconnectRequested) is TransitionResult.Accepted)
        assertTrue(ready.handle(DeviceEvent.DisconnectRequested) is TransitionResult.Ignored)
    }

    @Test
    fun `异常事件可从活跃状态进入 FAULT`() {
        val machine = DeviceStateMachine(DeviceState.READY)
        val result = machine.handle(DeviceEvent.FaultRaised("固件校验失败"))
        assertTrue(result is TransitionResult.Accepted)
        assertEquals(DeviceState.FAULT, machine.state)
        assertEquals(DeviceState.READY, (result as TransitionResult.Accepted).transition.from)
    }

    @Test
    fun `FAULT 状态下链路丢失回落未连接`() {
        val machine = DeviceStateMachine(DeviceState.FAULT)
        machine.handle(DeviceEvent.LinkLost)
        assertEquals(DeviceState.DISCONNECTED, machine.state)
    }

    @Test
    fun `迁移历史受容量限制`() {
        val machine = DeviceStateMachine(historyLimit = 3)
        machine.handle(DeviceEvent.ConnectRequested)
        machine.handle(DeviceEvent.LinkEstablished)
        machine.handle(DeviceEvent.AuthenticationStarted)
        machine.handle(DeviceEvent.AuthSucceeded)

        assertEquals(3, machine.transitions().size)
        assertEquals(DeviceState.READY, machine.transitions().last().to)
    }

    @Test
    fun `迁移回调收到完整事件`() {
        var observed: StateTransition? = null
        val machine = DeviceStateMachine(onTransition = { observed = it })

        machine.handle(DeviceEvent.ConnectRequested, timestampMillis = 1_234)

        val transition = observed
        assertTrue(transition != null)
        assertEquals(DeviceState.DISCONNECTED, transition.from)
        assertEquals(DeviceState.CONNECTING, transition.to)
        assertEquals(1_234L, transition.timestampMillis)
    }

    @Test
    fun `未连接状态收到链路丢失视为重复事件`() {
        val machine = DeviceStateMachine()
        assertTrue(machine.handle(DeviceEvent.LinkLost) is TransitionResult.Ignored)
    }
}
