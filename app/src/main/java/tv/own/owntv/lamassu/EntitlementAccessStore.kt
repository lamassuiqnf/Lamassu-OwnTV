package tv.own.owntv.lamassu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EntitlementAccessStore {
    private val _access = MutableStateFlow(EntitlementAccess.open())
    val access: StateFlow<EntitlementAccess> = _access.asStateFlow()

    fun current(): EntitlementAccess = _access.value

    fun update(access: EntitlementAccess) {
        _access.value = access
    }
}
