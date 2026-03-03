package oscarvarto.mx.api;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.junit.Options;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.TestInstance;

/// Base class for Playwright API tests that supports **reuse of authentication state**
/// between [APIRequestContext] and [BrowserContext], and centralizes browser
/// configuration via [TypeSafe Config](https://github.com/lightbend/config).
///
/// ## When to use
///
/// Use this base class when your API tests need to transition from API-level authentication
/// to browser-based testing while preserving the authenticated session. This is typical for
/// web apps that use **cookie-based or token-based authentication** where the authenticated
/// state is stored as cookies.
///
/// ## How it works
///
/// 1. Authenticate via API calls (e.g., POST to a login endpoint)
/// 2. Call [#createAuthenticatedBrowserContext(APIRequestContext, Browser)] to capture
///    the storage state (cookies, local storage) and create a pre-authenticated browser context
/// 3. Use the resulting [BrowserContext] to open pages that are already logged in
///
/// ## Scope of `storageState()`
///
/// `storageState()` captures **cookies and localStorage** — which covers
/// the vast majority of authentication models. It does **not** capture **session storage**
/// (`window.sessionStorage`), which is domain-specific and not persisted across page loads.
/// If your app relies on session storage for auth, you will need to save/restore it manually
/// via JavaScript evaluation.
///
/// @see <a href="https://playwright.dev/java/docs/auth#session-storage">
///     Playwright: Session storage workaround</a>
///
/// ## Browser configuration
///
/// Browser selection is read from `playwright.json` on the test classpath
/// (typically `src/test/resources/playwright.json`). The file uses a `playwright`
/// namespace with these keys:
///
/// | Key        | Values                              | Default    |
/// | ---------- | ----------------------------------- | ---------- |
/// | `browser`  | `chromium`, `firefox`, `webkit`     | `chromium` |
/// | `channel`  | `chrome`, `msedge`, etc. (optional) | _(none)_   |
/// | `headless` | `true`, `false`                     | `true`     |
///
/// `channel` is only relevant for Chromium and selects a specific browser
/// distribution (e.g., `chrome` for Google Chrome vs. Playwright's bundled Chromium).
///
/// Settings can be overridden via system properties without editing the file:
///
/// ```sh
/// ./gradlew test -Dplaywright.browser=firefox
/// ./gradlew test -Dplaywright.browser=chromium -Dplaywright.channel=chrome
/// ```
///
/// ## Subclass contract
///
/// Subclasses should annotate themselves with
/// `@UsePlaywright(MyOptionsFactory.class)` providing a custom
/// [OptionsFactory][com.microsoft.playwright.junit.OptionsFactory] that calls
/// [#browserOptions()] and layers service-specific settings on top.
///
/// @see <a href="https://playwright.dev/java/docs/browsers">
///     Playwright: Browsers</a>
/// @see <a href="https://playwright.dev/java/docs/api-testing#reuse-authentication-state">
///     Playwright: Reuse authentication state</a>
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class PlaywrightApiTest {

    private static final Config PW_CONFIG = loadPlaywrightConfig();

    private static Config loadPlaywrightConfig() {
        Config root = ConfigFactory.load("playwright");
        try {
            return root.getConfig("playwright");
        } catch (ConfigException.Missing e) {
            throw new IllegalStateException(
                    "playwright.json must contain a top-level \"playwright\" namespace, "
                            + "e.g. { \"playwright\": { \"browser\": \"chromium\" } }",
                    e);
        }
    }

    /// Builds an [Options] instance pre-configured with browser settings from
    /// `playwright.json`.
    ///
    /// [OptionsFactory][com.microsoft.playwright.junit.OptionsFactory] implementations
    /// should call this method and then chain service-specific setters:
    ///
    /// ```java
    /// public Options getOptions() {
    ///     return PlaywrightApiTest.browserOptions()
    ///             .setApiRequestOptions(new APIRequest.NewContextOptions()
    ///                     .setBaseURL("https://api.example.com")
    ///                     .setExtraHTTPHeaders(headers));
    /// }
    /// ```
    ///
    /// @return an [Options] with `browserName`, `channel`, and `headless` populated
    ///         from config (any absent keys are left as Playwright defaults)
    protected static Options browserOptions() {
        Options options = new Options();
        if (PW_CONFIG.hasPath("browser")) {
            options.setBrowserName(PW_CONFIG.getString("browser"));
        }
        if (PW_CONFIG.hasPath("channel")) {
            options.setChannel(PW_CONFIG.getString("channel"));
        }
        if (PW_CONFIG.hasPath("headless")) {
            options.setHeadless(PW_CONFIG.getBoolean("headless"));
        }
        return options;
    }

    /// Creates a [BrowserContext] pre-loaded with the storage state captured from an
    /// authenticated [APIRequestContext].
    ///
    /// Storage state is interchangeable between `APIRequestContext` and `BrowserContext`.
    /// This method captures cookies and local storage from the API context and transfers
    /// them to a new browser context, enabling seamless transition from API testing to
    /// UI testing within the same authenticated session.
    ///
    /// **Note:** the caller is responsible for closing the returned context (e.g., via
    /// try-with-resources) when it is no longer needed.
    ///
    /// ### Example
    ///
    /// ```java
    /// @Test
    /// void verifyDashboardAfterApiLogin(APIRequestContext api, Browser browser) {
    ///     api.post("/login", RequestOptions.create().setData(credentials));
    ///     try (BrowserContext ctx = createAuthenticatedBrowserContext(api, browser)) {
    ///         Page page = ctx.newPage();
    ///         page.navigate("https://example.com/dashboard");
    ///         assertThat(page.locator("h1")).hasText("Welcome");
    ///     }
    /// }
    /// ```
    ///
    /// @param apiContext an authenticated API request context whose storage state will be captured
    /// @param browser    the browser instance in which to create the new context
    /// @return a new [BrowserContext] carrying the API context's authentication state
    protected BrowserContext createAuthenticatedBrowserContext(APIRequestContext apiContext, Browser browser) {
        String state = apiContext.storageState();
        return browser.newContext(new Browser.NewContextOptions().setStorageState(state));
    }
}
