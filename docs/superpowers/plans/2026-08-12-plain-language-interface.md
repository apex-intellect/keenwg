# Plain-Language Interface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ambiguous navigation, actions, statuses, and technical implementation terms with one consistent task-oriented interface, while moving manual controls behind an opt-in expert mode.

**Architecture:** Keep backend identifiers and protocol models unchanged. Add presentation-only mappings at the Android UI boundary, enforce the vocabulary with JVM contract tests, and store the local expert-mode preference separately from router state. The existing advanced settings screen remains intact but is reachable only from the new About screen after explicit opt-in.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Navigation Compose, DataStore Preferences, JUnit 4, Android string resources.

## Global Constraints

- Primary copy names the user's task; technology names are secondary context.
- Visible actions use verb + object unless the object is unambiguous in the same row.
- Generic `Готово`, `Обновить`, `Проверить`, `Добавить`, and `Операция не выполнена` are not terminal user-facing operation messages.
- `Companion`, adapter IDs, source IDs, group IDs, state versions, operation keys, and raw error codes appear only in technical details.
- Russian and English resources change together.
- All touch targets remain at least 48 dp and icon-only controls have target-specific descriptions.
- This plan does not change router state, route semantics, or subscription transport.

---

### Task 1: Lock the vocabulary with contract tests

**Files:**
- Create: `app/src/test/java/ru/anisimov/keenwg/ui/InterfaceCopyContractTest.kt`
- Modify: `app/src/test/java/ru/anisimov/keenwg/ui/navigation/TopLevelDestinationTest.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/navigation/TopLevelDestination.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/navigation/KeenBottomIsland.kt`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: resource-backed top-level labels `Главная`, `VPN`, `Правила`, `Доступ`, `Настройки` and an ordinary-copy allowlist enforced from JVM tests.
- Consumes: existing `TopLevelDestination.entries` and XML resource files.

- [ ] **Step 1: Write the failing copy contract**

```kotlin
class InterfaceCopyContractTest {
    private val russian = Files.readString(Path.of("src/main/res/values-ru/strings.xml"))

    @Test fun `ordinary interface does not expose implementation vocabulary`() {
        val ordinary = russian.lineSequence()
            .filterNot { it.contains("technical", true) || it.contains("support_", true) || it.contains("expert_", true) }
            .joinToString("\n")
        listOf("Companion", "endpoint", "state version", "узл. · xkeen").forEach {
            assertFalse("forbidden copy: $it", ordinary.contains(it, ignoreCase = true))
        }
    }

    @Test fun `top-level and primary operation labels are explicit`() {
        listOf("Главная", "VPN", "Правила", "Доступ", "Настройки", "Обновить подписку", "Добавить устройство")
            .forEach { assertTrue("missing copy: $it", russian.contains(">$it<")) }
    }
}
```

- [ ] **Step 2: Update the navigation test and verify RED**

Remove the unused Russian `label` and `contentDescription` properties from `TopLevelDestination`; assert the stable route order instead:

```kotlin
assertEquals(listOf("overview", "connections", "routes", "access", "system"), TopLevelDestination.entries.map { it.routeKey })
```

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.InterfaceCopyContractTest" --tests "ru.anisimov.keenwg.ui.navigation.TopLevelDestinationTest"
```

Expected: FAIL because old labels and forbidden copy remain.

- [ ] **Step 3: Apply the top-level vocabulary**

Reduce the enum to locale-neutral route keys:

```kotlin
OVERVIEW("overview")
CONNECTIONS("connections")
ROUTES("routes")
ACCESS("access")
SYSTEM("system")
```

Set `nav_*` resources to the approved Russian copy and equivalent English labels `Home`, `VPN`, `Rules`, `Access`, and `Settings`. Keep `KeenBottomIsland` as the only label/description resource mapping.

- [ ] **Step 4: Run focused tests**

Run the command from Step 2. Expected: navigation test PASS; copy contract may still fail only on terms addressed in later tasks. Temporarily narrow the contract scan to the resource prefixes already migrated in this task (`nav_`).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/ru/anisimov/keenwg/ui/navigation/TopLevelDestination.kt app/src/main/res/values*/strings.xml app/src/test/java/ru/anisimov/keenwg/ui/InterfaceCopyContractTest.kt app/src/test/java/ru/anisimov/keenwg/ui/navigation/TopLevelDestinationTest.kt
git commit -m "ui: define plain-language navigation"
```

### Task 2: Make VPN sources and actions self-explanatory

**Files:**
- Create: `app/src/test/java/ru/anisimov/keenwg/ui/connections/ConnectionsPresentationTest.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/connections/ConnectionsPresentation.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/connections/ConnectionsState.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/connections/ConnectionsScreen.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/connections/ConnectionsViewModel.kt`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `sourceDisplayKind(CatalogSource): SourceDisplayKind`, `groupDisplayKind(CatalogGroup): GroupDisplayKind`, `connectionOperationNotice(result, error, nodeCount): ConnectionNotice`.
- Consumes: unchanged catalog IDs and error codes.

- [ ] **Step 1: Write failing presentation tests**

```kotlin
@Test fun `reserved backend labels are translated at presentation boundary`() {
    assertEquals(SourceDisplayKind.XKEEN_SUBSCRIPTION, sourceDisplayKind(source(id = "xkeen-subscription", label = "XKeen")))
    assertEquals(GroupDisplayKind.PRIMARY, groupDisplayKind(CatalogGroup("primary", "Primary", 0)))
}

@Test fun `operation notices name the completed or failed task`() {
    assertEquals(ConnectionNotice.SubscriptionUpdated(3), connectionOperationNotice("committed", null, 3))
    assertEquals(ConnectionNotice.SubscriptionDownloadFailed, connectionOperationNotice("rejected", "subscription_download_failed", 3))
    assertEquals(ConnectionNotice.RouterBusy, connectionOperationNotice("rejected", "busy", 3))
    assertEquals(ConnectionNotice.ResultUnconfirmed, connectionOperationNotice("uncertain", null, 3))
}
```

- [ ] **Step 2: Run the new test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.connections.ConnectionsPresentationTest"
```

Expected: FAIL because the presentation functions and sealed notice type do not exist.

- [ ] **Step 3: Add the pure presentation model**

```kotlin
sealed interface ConnectionNotice {
    data class SubscriptionUpdated(val serverCount: Int) : ConnectionNotice
    data object SubscriptionDownloadFailed : ConnectionNotice
    data object InvalidSubscription : ConnectionNotice
    data object RouterBusy : ConnectionNotice
    data object ReloadAndRetry : ConnectionNotice
    data object ResultUnconfirmed : ConnectionNotice
    data object ActionFailed : ConnectionNotice
}

internal enum class SourceDisplayKind { XKEEN_SUBSCRIPTION, CUSTOM }
internal enum class GroupDisplayKind { PRIMARY, CUSTOM }

internal fun sourceDisplayKind(source: CatalogSource) =
    if (source.id == "xkeen-subscription") SourceDisplayKind.XKEEN_SUBSCRIPTION else SourceDisplayKind.CUSTOM

internal fun groupDisplayKind(group: CatalogGroup) =
    if (group.id == "primary") GroupDisplayKind.PRIMARY else GroupDisplayKind.CUSTOM
```

Implement the error mapping exactly as asserted, including `stale_state` and `stale_adapter_state` to `ReloadAndRetry`.

- [ ] **Step 4: Render explicit source copy and progress**

Update the screen title/subtitle to `VPN-серверы` / `Подписки, страны и отдельные серверы`. Render source summaries as `N сервер/сервера/серверов`, never adapter ID. Change actions to `Добавить VPN`, `Обновить подписку`, `Обновляем подписку…`, `Проверить сервер`, and `Проверить сервер снова`.

Add source helper text:

```text
Загрузить актуальные страны и серверы. Текущий VPN-сервер не изменится.
```

Map `SourceDisplayKind`, `GroupDisplayKind`, and `ConnectionNotice` to localized resources in the Composable; use the backend label only for `CUSTOM`. Do not return localized strings from the ViewModel.

- [ ] **Step 5: Run presentation and ViewModel tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.connections.*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/ru/anisimov/keenwg/ui/connections app/src/main/res/values*/strings.xml app/src/test/java/ru/anisimov/keenwg/ui/connections
git commit -m "ui: clarify VPN subscription actions"
```

### Task 3: Rename rules segments and route operations

**Files:**
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/network/NetworkSegments.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/network/NetworkScreen.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/network/NetworkViewModel.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/network/DomainRuleSheet.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/network/DomainRuleCard.kt`
- Modify: `app/src/test/java/ru/anisimov/keenwg/ui/network/NetworkPresentationTest.kt`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `networkSegmentLabelResource(NetworkSegment): Int` for resource-backed `Устройства`, `Адреса`, `Сайты`, `Проверка`, `Наборы`.
- Consumes: unchanged `NetworkSegment` enum values.

- [ ] **Step 1: Write the failing segment-label test**

```kotlin
@Test fun `rule segments use task language`() {
    assertEquals(
        listOf(R.string.rules_devices, R.string.rules_addresses, R.string.rules_sites, R.string.rules_check, R.string.rules_sets),
        NetworkSegment.entries.map(::networkSegmentLabelResource),
    )
}
```

- [ ] **Step 2: Run and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.network.NetworkPresentationTest"
```

Expected: FAIL on `IP`, `Домены`, `Путь`, and `Сценарии`.

- [ ] **Step 3: Implement labels and full section headings**

Expose `networkSegmentLabelResource` as an internal pure function returning `R.string.rules_*`, and resolve it through `stringResource`. Use screen title `Правила маршрутизации` and subtitle `Что идёт через VPN и что открывается напрямую`. Use section headings `Домашние устройства`, `IP-адреса без VPN`, `Сайты без VPN`, `Проверка маршрута`, and `Готовые наборы правил`.

Rename visible actions to `Обновить список устройств`, `Добавить IP-адрес`, `Добавить сайт`, `Проверить маршрут`, `Посмотреть изменения`, `Применить правила`, and `Восстановить прежние правила`.

- [ ] **Step 4: Replace hard-coded implementation phrases**

Move hard-coded Russian strings from `NetworkScreen.kt`, `NetworkSegments.kt`, and domain components into both resource files. Keep `CIDR`, `GeoSite`, and module IDs only in helper text or technical detail.

- [ ] **Step 5: Run network tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.network.*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/ru/anisimov/keenwg/ui/network app/src/main/res/values*/strings.xml app/src/test/java/ru/anisimov/keenwg/ui/network
git commit -m "ui: clarify routing rules and recovery"
```

### Task 4: Clarify Home and remote-access language

**Files:**
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/overview/OverviewScreen.kt`
- Create: `app/src/main/java/ru/anisimov/keenwg/ui/overview/OverviewPresentation.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/peers/PeerListScreen.kt`
- Create: `app/src/main/java/ru/anisimov/keenwg/ui/peers/PeerStatusPresentation.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/detail/PeerDetailScreen.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/add/AddPeerScreen.kt`
- Modify: `app/src/test/java/ru/anisimov/keenwg/ui/peers/PeerStatusPresentationTest.kt`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: task-oriented overview module labels and remote access actions.
- Consumes: existing health and peer state; no repository changes.

- [ ] **Step 1: Add failing status-language assertions**

Assert that connected peers render `Подключено сейчас`, peers with recent observations render `Недавно подключалось`, and peers without evidence render `Нет данных о подключении`.

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.peers.PeerStatusPresentationTest" --tests "ru.anisimov.keenwg.ui.overview.OverviewViewModelTest"
```

- [ ] **Step 3: Update Home copy**

Use `Роутер доступен`, `Телефон подключён к роутеру`, `Активный VPN-сервер`, `VPN-серверы`, `Правила маршрутизации`, and `Удалённый доступ`. Remove visible `Companion подключён` and `Текущий маршрут XKeen` wording.

- [ ] **Step 4: Update remote-access copy**

Use title `Удалённый доступ`, subtitle `Телефоны и устройства, которые подключаются к дому через WireGuard`, primary action `Добавить устройство`, and explicit detail-menu actions `Переименовать устройство`, `Показать настройки подключения`, `Обновить ключ`, `Отключить доступ`, and `Удалить устройство`.

- [ ] **Step 5: Run peer and overview suites**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.peers.*" --tests "ru.anisimov.keenwg.ui.detail.*" --tests "ru.anisimov.keenwg.ui.add.*" --tests "ru.anisimov.keenwg.ui.overview.*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/ru/anisimov/keenwg/ui/overview app/src/main/java/ru/anisimov/keenwg/ui/peers app/src/main/java/ru/anisimov/keenwg/ui/detail app/src/main/java/ru/anisimov/keenwg/ui/add app/src/main/res/values*/strings.xml app/src/test/java/ru/anisimov/keenwg/ui
git commit -m "ui: clarify home and remote access"
```

### Task 5: Move manual controls behind opt-in expert mode

**Files:**
- Create: `app/src/main/java/ru/anisimov/keenwg/data/store/ExpertModeStore.kt`
- Create: `app/src/main/java/ru/anisimov/keenwg/ui/about/AboutScreen.kt`
- Create: `app/src/main/java/ru/anisimov/keenwg/ui/about/AboutViewModel.kt`
- Create: `app/src/main/java/ru/anisimov/keenwg/ui/system/RouterConnectionScreen.kt`
- Create: `app/src/main/java/ru/anisimov/keenwg/ui/system/RouterConnectionPresentation.kt`
- Create: `app/src/test/java/ru/anisimov/keenwg/data/store/ExpertModeStoreTest.kt`
- Create: `app/src/test/java/ru/anisimov/keenwg/ui/about/AboutViewModelTest.kt`
- Create: `app/src/test/java/ru/anisimov/keenwg/ui/system/RouterConnectionPresentationTest.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/data/ServiceLocator.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/system/SystemPresentation.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/system/SystemScreen.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/KeenWgNav.kt`
- Modify: `app/src/test/java/ru/anisimov/keenwg/ui/system/SystemPresentationTest.kt`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `ExpertModeStore.enabled: Flow<Boolean>`, `suspend fun setEnabled(Boolean)`, `AboutRoute`, `RouterConnectionRoute`, and `SystemAction.ABOUT`.
- Consumes: existing `AdvancedSettingsRoute` without changing `SettingsScreen` persistence.

- [ ] **Step 1: Write failing store and menu tests**

```kotlin
@Test fun `expert mode defaults off and persists explicit opt-in`() = runTest {
    val store = ExpertModeStore(preferenceDataStoreFactory(scope = backgroundScope) { tempFile.newFile() })
    assertFalse(store.enabled.first())
    store.setEnabled(true)
    assertTrue(store.enabled.first())
    store.setEnabled(false)
    assertFalse(store.enabled.first())
}
```

Update `SystemPresentationTest` to expect `ABOUT` and never `ADVANCED` in the normal menu.

- [ ] **Step 2: Run and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.data.store.ExpertModeStoreTest" --tests "ru.anisimov.keenwg.ui.system.SystemPresentationTest"
```

- [ ] **Step 3: Implement preference isolation**

```kotlin
class ExpertModeStore(private val dataStore: DataStore<Preferences>) {
    private val expertMode = booleanPreferencesKey("expert_mode")
    val enabled: Flow<Boolean> = dataStore.data.map { it[expertMode] ?: false }
    suspend fun setEnabled(value: Boolean) { dataStore.edit { it[expertMode] = value } }
}
```

Use a separate `Context.expertModeDataStore by preferencesDataStore("ui_preferences")`. Do not add the key to `SettingsKeys` or reuse the router-profile DataStore. Construct it from `applicationContext`; this preference file never contains router profiles or secrets.

- [ ] **Step 4: Add About and the warning gate**

The About screen shows app version, open-source repository link, licenses, and `Режим эксперта`. Turning it on first opens a confirmation explaining that manual router addresses, ports, tokens, and WireGuard fields can break connectivity. Only after confirmation does it call `setEnabled(true)`. When enabled, show `Открыть ручные настройки`; disabling expert mode only changes the local boolean.

- [ ] **Step 5: Remove Advanced from normal Settings navigation**

Replace `SystemAction.ADVANCED` with `SystemAction.ABOUT`. Add `AboutRoute`; navigate to `AdvancedSettingsRoute` only from `AboutScreen` when `enabled == true`.

Use normal Settings rows: `Подключение к роутеру`, `Подключённые телефоны`, `Диагностика`, `Резервная копия`, `О приложении`. Change connection body to `Проверить связь или подключить другой роутер`.

Add `RouterConnectionRoute` and `RouterConnectionScreen`. A healthy profile shows `Телефон подключён к роутеру`, the last successful check, primary action `Проверить подключение`, and secondary action `Подключить другой роутер`. The primary action calls `OverviewViewModel.refresh()` and never navigates to setup or requests credentials. The secondary action first confirms that the user is intentionally changing routers, then opens `SetupRoute`. A broken/revoked profile shows `Восстановить подключение` with an explanation that the router login and password are needed again because protected access no longer works.

Add pure presentation tests:

```kotlin
@Test fun `healthy connection never offers credential prompt as primary action`() {
    val model = routerConnectionPresentation(OverviewHealth.HEALTHY)
    assertEquals(RouterConnectionAction.CHECK, model.primaryAction)
    assertEquals(RouterConnectionAction.CHANGE_ROUTER, model.secondaryAction)
}

@Test fun `missing protected connection explains recovery credentials`() {
    val model = routerConnectionPresentation(OverviewHealth.SETUP_REQUIRED)
    assertEquals(RouterConnectionAction.RECOVER, model.primaryAction)
    assertTrue(model.credentialsExplanationRequired)
}
```

- [ ] **Step 6: Run tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.data.store.ExpertModeStoreTest" --tests "ru.anisimov.keenwg.ui.about.*" --tests "ru.anisimov.keenwg.ui.system.*" --tests "ru.anisimov.keenwg.ui.navigation.*"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/ru/anisimov/keenwg/data app/src/main/java/ru/anisimov/keenwg/ui/about app/src/main/java/ru/anisimov/keenwg/ui/system app/src/main/java/ru/anisimov/keenwg/ui/KeenWgNav.kt app/src/main/res/values*/strings.xml app/src/test/java/ru/anisimov/keenwg
git commit -m "ui: gate manual controls behind expert mode"
```

### Task 6: Complete the action and accessibility inventory

**Files:**
- Modify: all touched `app/src/main/java/ru/anisimov/keenwg/ui/**/*.kt` files containing `Button`, `TextButton`, `OutlinedButton`, `IconButton`, `DropdownMenuItem`, or `clickable`
- Modify: `app/src/test/java/ru/anisimov/keenwg/ui/InterfaceCopyContractTest.kt`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: a repository-wide enforceable ordinary-interface copy contract.
- Consumes: migrated copy from Tasks 1-5.

- [ ] **Step 1: Expand the contract to all ordinary UI prefixes**

Scan `nav_`, `system_`, `ui_connectionsscreen_`, `ui_networkscreen_`, `ui_overviewscreen_`, `ui_peerlistscreen_`, `ui_peerdetailscreen_`, `access_`, and `setup_`. Allow technical vocabulary only in keys containing `technical`, `expert`, or `support`.

Add assertions that these exact ambiguous values are absent as standalone element text: `Обновить`, `Проверить`, `Добавить`, `Готово`, `Операция не выполнена`, `Primary`, and `3 узл.`.

- [ ] **Step 2: Run and collect RED failures**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.InterfaceCopyContractTest"
```

Expected: FAIL with the remaining resource names.

- [ ] **Step 3: Fix every reported ordinary action**

For each failure, rename the action with its object, replace generic success/error text, and add target-specific `contentDescription`. Do not weaken the contract to allow an ambiguous primary action.

- [ ] **Step 4: Run the full JVM suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Build debug APK**

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` and a generated debug APK.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main app/src/test
git commit -m "ui: complete interface language audit"
```

### Task 7: Verify responsive and assistive behavior

**Files:**
- Modify only files found defective by the checks in this task.

**Interfaces:**
- Produces: delivery evidence for compact screens, large text, navigation insets, and TalkBack labels.
- Consumes: final UI from Tasks 1-6.

- [ ] **Step 1: Run the existing layout contracts**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.RootScaffoldInsetsTest" --tests "ru.anisimov.keenwg.ui.navigation.BottomIslandInteractionTest"
```

- [ ] **Step 2: Inspect all icon-only actions**

```powershell
rg -n "IconButton\(|contentDescription = null|Icon\([^\n]+, null" app/src/main/java/ru/anisimov/keenwg/ui
```

Every icon-only action must have a localized target-specific description; decorative icons inside labeled controls may remain null.

- [ ] **Step 3: Run full verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
git diff --check
```

Expected: all commands exit 0.

- [ ] **Step 4: Commit any verification fixes**

```powershell
git add app/src/main app/src/test
git commit -m "fix: polish accessible interface copy"
```
