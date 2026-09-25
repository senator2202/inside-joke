# Inside Joke

Браузерная AI-партийная игра для компаний от 3 до 8 человек. Хост открывает комнату на общем экране (ТВ, ноутбук),
гости входят с телефонов по QR-коду или коду комнаты, AI-ведущий строит раунды из того, что игроки о себе рассказали.
Интерфейс на английском и русском.

**Версия:** 0.8.2. Что изменилось от версии к версии — в [CHANGELOG.md](CHANGELOG.md), как нумеруются версии и
выпускаются релизы — в [docs/VERSIONING.md](docs/VERSIONING.md), соглашения о коде — в
[docs/CONVENTIONS.md](docs/CONVENTIONS.md). Запущенный сервис сообщает версию в `GET /api/status`.

Письма с кодом входа можно отправлять через SMTP (в том числе Gmail) или Resend — настройка в [docs/MAIL.md](docs/MAIL.md).

## Стек

- **Backend:** Java 21, Spring Boot 4.1 (модульный монолит `com.insidejoke`), PostgreSQL + Flyway, WebSocket.
  Состояние игры живёт в памяти, в БД только аккаунты, деньги, итоги игр, затраты AI.
- **Frontend:** React 19, TypeScript, Vite, React Router. Собирается Maven-модулем `frontend` и упаковывается
  в jar backend'а как статические ресурсы.
- **Внешние сервисы** (все через реальные HTTP-клиенты, ключи из переменных окружения): Anthropic (раунды, реплики,
  модерация), OpenAI-совместимый TTS (голос ведущего), Paddle Billing (оплата), Resend (письма с кодом входа),
  Google OAuth, PostHog (аналитика).

## Структура

```
pom.xml                 родительский POM: модули frontend и backend
backend/                Spring Boot приложение
  src/main/java/com/insidejoke/
    auth/               вход по Google и 6-значному коду, сессии, аккаунт
    billing/            пассы, лимиты, Paddle (вебхуки, checkout)
    game/               движок игры, фазы, роли, снимки состояния
    room/               WebSocket, REST комнат, отзывы
    ai/                 шлюз к LLM и TTS, бюджеты, кэш аудио
    moderation/         правила контента
    analytics/          события в PostHog
    admin/              метрики и админ-API
    common/             ошибки, настройки, конфигурация
  src/main/resources/   application.yml, миграции Flyway, промпты, запасной контент
frontend/               React-приложение (src/pages: host, screen, play, audience, admin, system)
  public/               favicon, черновики условий и политики конфиденциальности
.run/                   готовые конфигурации запуска для IntelliJ IDEA
docker-compose.yml      PostgreSQL для локальной разработки
.env.example            все переменные окружения с пояснениями
```

## Требования

- **JDK 21.**
- **Node.js 22.12+ и npm в PATH.** Модуль `frontend` вызывает системный `npm`. Без Node соберётся только backend
  с флагом `-Dfrontend.skip=true`, при этом в jar попадёт предыдущая сборка фронтенда, если она была.
- **Docker** — только для локальной базы через `docker compose`. Тестам он не нужен: они поднимают встроенный PostgreSQL.
- Maven ставить не нужно, в проекте есть wrapper: `./mvnw` в macOS и Linux, `.\mvnw.cmd` в Windows.
- Команды ниже даны для bash (macOS, Linux, Git Bash) и для Windows PowerShell. В Windows PowerShell 5.1 нет `&&`,
  а параметры Maven с точкой (`-Dfrontend.skip=true`) нужно брать в кавычки, иначе PowerShell разрежет их по точке.

## Открыть в IntelliJ IDEA

1. **File → Open**, выбрать корневой `pom.xml`, **Open as Project**. IntelliJ импортирует оба Maven-модуля.
2. **File → Project Structure → Project SDK:** JDK 21.
3. В списке конфигураций запуска появятся конфигурации из папки `.run`:
   - **Backend (local)** — запускает приложение с профилем `local` из корня проекта;
   - **Frontend dev server** — `npm run dev` (конфигурация npm доступна в IntelliJ IDEA Ultimate; в Community выполните
     `npm run dev` в терминале из папки `frontend`);
   - **Verify (all checks)** — полная проверка (`./mvnw -B verify`, в Windows `.\mvnw.cmd -B verify`);
   - **Backend tests** — только тесты backend'а.

## Локальный запуск

bash (macOS, Linux):

```bash
docker compose up -d                 # PostgreSQL на localhost:5432
cp .env.example .env                 # при желании добавьте ключи AI, Paddle и т. д.
```

PowerShell (Windows):

```powershell
docker compose up -d
Copy-Item .env.example .env
```

Затем запустите **Backend (local)** в IntelliJ или из терминала, из корня проекта, чтобы прочитался `.env`.

bash:

```bash
./mvnw -B -pl backend -am -Dfrontend.skip=true -DskipTests package
SPRING_PROFILES_ACTIVE=local java -jar backend/target/inside-joke.jar
```

PowerShell:

```powershell
.\mvnw.cmd -B -pl backend -am '-Dfrontend.skip=true' -DskipTests package
$env:SPRING_PROFILES_ACTIVE = "local"
java -jar backend\target\inside-joke.jar
```

И дев-сервер фронтенда в отдельном терминале (http://localhost:5173, запросы к `/api` и `/ws` проксируются на :8080).

bash:

```bash
cd frontend && npm ci && npm run dev
```

PowerShell:

```powershell
cd frontend
npm ci
npm run dev
```

Как это работает локально:

- **Вход.** Введите любой email: 6-значный код будет напечатан в логе backend'а в строке
  `Development email to …: Your Inside Joke code: 123 456` (профиль `local` включает `MAIL_PROVIDER=log`).
- **Админка.** `admin@example.com` получает роль администратора; адреса задаются в `APP_ADMIN_EMAILS`.
  Панель — `/admin`.
- **Без ключей AI** игра работает на встроенном запасном контенте, без голоса ведущего. Секреты в этом режиме
  проверяются только правилами и в раунды не попадают: их использует лишь AI-ведущий. В продакшене
  (`AI_REQUIRE_LLM_MODERATION=true`) без ключа секреты выключаются, и телефоны их не предлагают.
- **Игра с телефонов в той же Wi-Fi-сети.** Укажите в `.env` `APP_PUBLIC_URL=http://<IP компьютера>:5173`, добавьте этот
  адрес в `APP_ALLOWED_ORIGINS` и запустите дев-сервер как `npm run dev -- --host`.
- `.env` читается из рабочей директории (корень проекта). Maven-тесты запускаются из `backend/` и этот файл не видят,
  поэтому ваши ключи не попадут в тесты.

## Проверки

```bash
./mvnw -B verify              # bash
```

```powershell
.\mvnw.cmd -B verify          # PowerShell
```

Одна команда проверяет всё: формат кода (Spotless, Prettier), checkstyle, модульные тесты бэкенда (Surefire, фаза
`test`), интеграционные тесты на встроенном PostgreSQL (Failsafe, фаза `verify`), а также typecheck, ESLint, сборку и
тесты фронтенда. На момент сдачи: **backend 324 теста (205 модульных и 119 интеграционных), frontend 127 тестов, 0 падений, 0 нарушений checkstyle**.

Покрытие считает JaCoCo: `backend/target/site/jacoco/index.html` — только модульные тесты (после `test`),
`backend/target/site/jacoco-all/index.html` — все тесты вместе (после `verify`). Правила игры (пакет `game`) должны быть
покрыты модульными тестами не меньше чем на 90 % строк и 75 % ветвлений, иначе `test` падает. Как устроены тесты — в
[docs/CONVENTIONS.md](docs/CONVENTIONS.md), раздел «Тесты».

Отдельные проверки и исправления.

bash:

```bash
./mvnw -B -pl backend -am -Dfrontend.skip=true test      # модульные тесты backend (секунды)
./mvnw -B -pl backend -am -Dfrontend.skip=true verify    # + интеграционные тесты
./mvnw -pl backend spotless:apply                        # отформатировать Java
cd frontend && npm run typecheck && npm run lint && npm test && npm run format:check
```

PowerShell:

```powershell
.\mvnw.cmd -B -pl backend -am '-Dfrontend.skip=true' test
.\mvnw.cmd -B -pl backend -am '-Dfrontend.skip=true' verify
.\mvnw.cmd -pl backend spotless:apply
cd frontend
npm run typecheck
npm run lint
npm test
npm run format:check
```

Типы контракта API для фронтенда (`frontend/src/lib/api/contract.gen.ts`) генерируются из Java. Если сборка сообщает, что
файл устарел, перегенерируйте его: `./mvnw -pl backend test -Dtest=ContractTypesTest -Dcontract.update=true`
(в PowerShell параметры с точкой — в кавычках).

## Языки

Есть два независимых выбора.

- **Язык игры** — настройка комнаты (`en`, `ru`). Хост выбирает его на странице «New party», в лобби его можно
  сменить до старта. На нём AI-ведущий пишет задания, реплики и титулы, на нём говорит голос. На нём же запасной
  контент (`backend/src/main/resources/content/fallback.<код>.json`) и надписи вроде «[Нет ответа]».
- **Язык интерфейса** — на каждом устройстве свой: список 🌐 в шапке, на телефоне и на общем экране. Выбор
  запоминается в браузере. Пока человек язык не выбрал, в игре интерфейс следует языку комнаты, вне игры — языку
  браузера.

Письмо с кодом входа приходит на языке интерфейса. Админка и юридические страницы — только на английском.

Как добавить язык:

1. `Language` в `backend/.../game/Language.java`: код, английское название (для промптов), начало вопроса «Кто из нас».
2. `content/fallback.<код>.json` с той же структурой, что `fallback.json`. `FallbackContentTest` проверит полноту.
   Имя игрока подставляется без склонения, поэтому шаблоны должны быть верны для любого имени и пола.
3. Правила модерации для языка в `ContentRuleUtils.java` (темы, адреса), тексты письма в `EmailLoginService`.
4. Новая миграция, расширяющая ограничение `ck_game_session_language`.
5. `frontend/src/locales/<код>.ts` со всеми ключами из `en.ts` (TypeScript и `dictionaries.test.ts` проверят) и
   запись в `LANGUAGES` в `frontend/src/lib/i18n.tsx`.

## Сборка и деплой

- Артефакт: `backend/target/inside-joke.jar` (фронтенд внутри). Запуск: `java -jar inside-joke.jar`.
- **Образ Docker** (`Dockerfile`): внутри тот же jar и JRE 21, запуск от непривилегированного пользователя, порт 8080,
  проверка здоровья — `GET /actuator/health`. Тесты при сборке образа не запускаются — это делает CI.
  ```bash
  docker build -t inside-joke .
  docker run --env-file .env -p 8080:8080 inside-joke
  ```
  Всё приложение с базой локально: `docker compose --profile app up -d --build` (профиль `local`, адрес
  http://localhost:8080; настройки из `.env`, если он есть). Без `--profile app` compose по-прежнему поднимает только
  базу.
- **CI** (`.github/workflows/ci.yml`, GitHub Actions): на каждый push в `main` и `develop` и на каждый pull request —
  `./mvnw -B verify` и сборка образа. Отчёты тестов (при падении) и покрытия — в артефактах запуска. Чтобы проверка
  была обязательной для слияния: **Settings → Branches → Add branch protection rule** для `main` (и `develop`) →
  **Require status checks to pass** → отметить `verify`.
- Все настройки — переменные окружения, полный список с пояснениями в `.env.example`. В продакшене обязательно
  `APP_COOKIE_SECURE=true` (по умолчанию), `APP_PUBLIC_URL=https://…`, `AI_REQUIRE_LLM_MODERATION=true`.
- **Paddle:** URL вебхука `https://<домен>/api/webhooks/paddle`, события `transaction.completed`, `adjustment.created`,
  `adjustment.updated`. Секрет подписи — в `PADDLE_WEBHOOK_SECRET`.
- **Google OAuth:** redirect URI `https://<домен>/login/oauth2/code/google`.
- **Перед деплоем** включите режим слива в `/admin → Flags & drain`: новые комнаты перестанут создаваться, текущие
  доиграют. Деплойте, когда счётчик живых комнат дойдёт до нуля. Комнаты хранятся в памяти, поэтому перезапуск сервера
  завершает все идущие игры; это решение из blueprint.
- `/admin → Payment issues`: события Paddle, которые не удалось обработать (неизвестная цена или аккаунт; после
  исправления причины — кнопка **Replay**), и возвраты/чарджбэки, которые сервер получил, но не применил, с причиной
  (ждёт одобрения, отклонён, частичный, предупреждение о споре, спор отменён) и кнопкой отзыва пасса.
- `/admin → AI`: задан ли ключ Anthropic, модель, последний вызов и сбой с причиной, живые лимиты скорости; журнал
  всех вызовов AI с фильтрами (даты, назначение, итог, «только сбои») и текстом ошибки.
- `/admin → Passes`: журнал всех пассов — купленных и выданных — с фильтрами (тип, источник, статус, даты, email),
  сортировкой, пагинацией и сводкой (продано, выдано, возвраты и чарджбэки, выручка брутто/нетто по валютам).

## Что нужно сделать перед запуском

- **Проверить интеграцию Paddle на sandbox-аккаунте.** Имена событий, поля возвратов и формат подписи реализованы
  по документации Paddle Billing, но с живым Paddle не проверялись.
- **Юридические тексты** (`frontend/public/terms.html`, `privacy.html`) — черновики. Выделенные поля `[…]` нужно
  заполнить, а тексты должен проверить юрист. Отдельно стоит посмотреть: согласие на анонимный идентификатор аналитики
  в ЕС/UK, права потребителя на возврат за цифровые товары, возрастные пороги.
- **Демо на лендинге** — анимированная сцена на CSS (`frontend/src/pages/host/DemoReel.tsx`). Когда появится запись
  реальной игры, её можно подставить вместо сцены.
- **Сравнение с Jackbox** на лендинге и в окне выбора пасса («вдвое дешевле Jackbox Party Pack») взято
  из спецификации — проверьте актуальные цены.
- **TTS-провайдер и цены AI** (`ANTHROPIC_*_MICROS_PER_TOKEN`, `TTS_MICROS_PER_CHAR`) нужно выставить по фактическим
  тарифам: от них зависят учёт затрат и дневной бюджет бесплатных игр.
- **Не входит в MVP** (из blueprint): подключение Sentry, нагрузочный тест k6, регулярная проверка юмора на наборе из
  50 тестовых досье.
