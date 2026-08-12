# KeenWG interface language audit

**Date:** 2026-08-12  
**Status:** approved design, awaiting written-spec review  
**Scope:** Android application UI, Russian and English resources, and the minimal Companion contract needed to explain subscription refresh safely

## Goal

Make every visible section, action, state, and recovery path understandable to a person who installed XKeen but does not know its internal architecture. A user must be able to predict what a tap will do without knowing what Companion, an adapter, a node, a state version, or a controller is.

No existing router-management capability is removed. Technical controls remain available only when the user explicitly enables expert mode.

## Product-language principles

1. Primary copy names the user's task. Technology names are secondary context.
2. Every actionable label uses **verb + object** when the object is not visually unambiguous.
3. A mutation lasting longer than 300 ms shows what is happening and disables duplicate taps.
4. Success states name the completed result. Generic `Готово` is not used as an operation result.
5. Errors state what failed, whether anything changed, and the next safe action.
6. Internal identifiers never appear in ordinary UI: adapter IDs, source IDs, group IDs, state versions, operation keys, raw error codes, `Companion`, `endpoint`, and `node` belong only in technical details.
7. The same operation has the same name on every screen.
8. Sensitive values such as subscription URLs, credentials, tokens, and full configurations never appear in reports, logs, previews, or accessibility text.

## Information architecture

| Current navigation | New navigation | Screen title | Purpose shown to the user |
|---|---|---|---|
| Обзор | Главная | KeenWG | Router availability, active VPN server, and available modules |
| Связи | VPN | VPN-серверы | Subscriptions, countries, and manually added VPN servers |
| Маршруты | Правила | Правила маршрутизации | What uses VPN and what goes directly |
| Доступ | Доступ | Удалённый доступ | Phones and devices connecting home through WireGuard |
| Система | Настройки | Настройки | Phone/router connection, connected phones, diagnostics, backup, and app information |

The compact bottom-navigation label remains `Доступ` to fit five destinations. Its accessibility label and screen title are `Удалённый доступ`.

### Rules screen

The five compact segments and their full section titles are:

| Segment | Section title |
|---|---|
| Устройства | Домашние устройства |
| Адреса | IP-адреса без VPN |
| Сайты | Сайты без VPN |
| Проверка | Проверка маршрута |
| Наборы | Готовые наборы правил |

`GeoSite`, CIDR, Xray, and XKeen may appear in field help or technical details, never as the only explanation of an action.

## Global action vocabulary

| Ambiguous label | Replacement |
|---|---|
| Обновить | Name the target: `Обновить подписку`, `Обновить список устройств`, `Обновить состояние роутера` |
| Проверить | Name the target: `Проверить сервер`, `Проверить подключение`, `Проверить маршрут` |
| Добавить | Name the object: `Добавить VPN`, `Добавить устройство`, `Добавить сайт`, `Добавить IP-адрес` |
| Сохранить | Use the result: `Добавить сервер`, `Сохранить правило`, `Сохранить имя` |
| Применить | Use `Применить правила` or `Восстановить настройки` |
| Повторить | Name the retry: `Повторить загрузку`, `Повторить проверку`, `Повторить подключение` |
| Готово | State the result: `Подписка обновлена`, `Правила применены`, `Устройство добавлено` |
| Операция не выполнена | State the failed task and provide one recovery action |

Icon-only controls receive target-specific accessibility descriptions. Refresh icons never share the generic description `Обновить`.

## VPN and subscription refresh

The source card uses this hierarchy:

- title: `Подписка XKeen`;
- summary: `3 сервера · обновлено сегодня, 20:04`;
- helper text: `Загрузить актуальные страны и серверы. Текущий VPN-сервер не изменится.`;
- idle action: `Обновить подписку`;
- progress action: `Обновляем подписку…`;
- success: `Подписка обновлена: найдено 3 сервера`.

`xkeen`, `3 узл.`, and raw source/group labels are never rendered. The reserved `primary` group is presented as `Основные`.

If the router has an existing XKeen cache but no saved subscription URL, the card shows:

- status: `Ссылка подписки не указана`;
- explanation: `Сохранённые серверы доступны, но KeenWG не сможет получить свежий список.`;
- action: `Добавить ссылку`.

The link-entry screen is protected against screenshots, sends the URL only over pinned HTTPS, and does not persist the raw URL on the phone. Companion stores it with owner-only permissions and exposes only a `configured` boolean. Replacing a link requires an explicit `Заменить ссылку` action and confirmation that the current server will not switch automatically.

## Error and recovery language

Known backend outcomes map to stable user-facing copy:

| Condition | Message | Recovery |
|---|---|---|
| Subscription URL absent | `Ссылка подписки не указана` | `Добавить ссылку` |
| Download failed | `Не удалось скачать подписку` | Explain that the router needs internet; `Повторить загрузку` |
| Invalid provider response | `Сервис вернул неподдерживаемый список серверов` | `Проверить ссылку` |
| Router is busy | `Роутер завершает другую операцию` | Wait and refresh status automatically |
| Stale local state before mutation | Do not expose the technical conflict | Reload once automatically; otherwise ask to repeat the named action |
| Protected connection unavailable | `Телефон не может связаться с роутером` | `Проверить подключение` |
| Permission revoked | `Доступ этого телефона отозван` | `Подключить телефон заново` |
| Result uncertain | `Не удалось подтвердить результат` | State that new changes are paused and offer diagnostics/recovery |

Errors appear next to the failed action rather than only in a generic page-level `Статус` card. Technical codes remain available in a collapsed `Технические подробности` section and sanitized support report.

## Main screen

Use direct state language:

- `Роутер доступен` / `Роутер недоступен`;
- `Телефон подключён к роутеру`;
- `Активный VPN-сервер`;
- module names: `VPN-серверы`, `Правила маршрутизации`, and `Удалённый доступ`.

The overview does not duplicate full lists or expose implementation status such as `Companion подключён`.

## Remote access

The screen title is `Удалённый доступ`. Its subtitle explains: `Телефоны и устройства, которые подключаются к дому через WireGuard`.

- `Создать доступ` becomes `Добавить устройство`.
- Peer state uses `Подключено сейчас`, `Недавно подключалось`, or `Нет данных о подключении`.
- Menu actions explicitly name their effect: `Переименовать устройство`, `Показать настройки подключения`, `Обновить ключ`, `Отключить доступ`, and `Удалить устройство`.
- Destructive confirmations state which device loses access and whether recovery requires creating a new configuration.

## Settings and credentials

The normal Settings screen contains:

1. `Подключение к роутеру` with the current state and `Проверить подключение`;
2. `Подключённые телефоны`;
3. `Диагностика`;
4. `Резервная копия`;
5. `О приложении`.

`Расширенные настройки` are removed from the normal list. The user enters router credentials during first connection only. Ordinary VPN, routing, device, diagnostics, and backup operations use the established protected connection and never request the password again.

The setup password is cleared from application memory after the first connection and is not retained for later operations.

Credentials may be requested again only when the user explicitly:

- connects a different router;
- restores a completely broken or revoked protected connection.

Before such a prompt, the UI explains why credentials are required and what will change. A healthy session never shows SSH, fingerprint, certificate, token, endpoint, or port vocabulary. Companion updates use the existing protected channel and a verified release bundle; they do not request router credentials while that channel is healthy.

Manual router, WireGuard, Collector, port, and history fields move to `О приложении → Режим эксперта`. Expert mode is off by default, requires an explicit warning acknowledgement, and can be disabled without changing the saved router configuration.

## Feedback and accessibility

- Buttons that launch network operations show inline progress and remain the same width.
- Duplicate taps are disabled until a terminal result is known.
- All Android touch targets are at least 48 dp.
- Icon-only actions have meaningful content descriptions including the target.
- Status is conveyed with text and icon, never color alone.
- Long labels wrap at large font scales; technical values use a monospaced style only in details.
- Screen-reader announcements use the same plain-language result as visible feedback and never include secrets.
- Bottom navigation and fixed controls respect system bars and do not cover scroll content.

## Implementation boundaries

- Existing router-management functionality remains available.
- This work does not change route-selection semantics or automatically switch VPN servers.
- The Companion API may add a minimal protected subscription-configuration capability, but must never return the URL.
- The Companion API may add an owner-only verified update capability so a healthy paired phone never falls back to SSH credentials for routine updates.
- Existing raw backend error codes remain stable for protocol compatibility and are translated in the Android presentation layer.
- Reserved backend labels such as `primary` and adapter IDs are localized in presentation code rather than rewritten in persisted router state.
- Russian and English resources are updated together.

## Acceptance tests

1. Automated inventory test fails if an ordinary UI resource contains forbidden internal terms without an approved technical-details context.
2. Every visible button in the five top-level destinations has a target-specific label or accessibility description.
3. Subscription refresh tests cover idle, progress, success, missing-link, download failure, invalid response, busy, stale state, and uncertain result.
4. A refresh does not activate a different server and the UI states this before the action.
5. Existing `primary` and `xkeen` backend labels are not displayed raw.
6. With expert mode off, manual address, port, token, Collector, and credential fields are unreachable from normal navigation.
7. A healthy paired phone can use all supported modules without a password prompt.
8. Expert mode can be enabled and disabled without mutating router state.
9. Tests cover compact phone, largest font scale, landscape, TalkBack semantics, gesture navigation, and dark-theme contrast.
10. Sanitized reports and logs contain no password, token, subscription URL, VLESS URI, private key, or full configuration.

## Non-goals

- Rebranding XKeen or WireGuard.
- Hiding technology names from technical documentation or expert mode.
- Automatically formatting storage, installing XKeen, or switching the active VPN server during subscription refresh.
- Removing expert capabilities from the open-source project.
