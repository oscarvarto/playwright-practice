package oscarvarto.mx.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.junit.Options;
import com.microsoft.playwright.junit.OptionsFactory;
import com.microsoft.playwright.junit.UsePlaywright;
import com.microsoft.playwright.options.RequestOptions;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import oscarvarto.mx.secrets.SecretsProvider;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/// Tests for the GitHub Issues API using Playwright's API testing support.
///
/// Demonstrates:
/// - Creating and deleting GitHub repositories via API as test fixtures
/// - Creating issues via API and verifying them through the same API
/// - Mixing API calls with browser-based assertions using [Page]
///
/// Extends [PlaywrightApiTest] to inherit the authentication state reuse
/// utility for tests that need both API and browser-based verification.
///
/// ## Prerequisites
///
/// This test class is **opt-in** and only runs when
/// `-Dplaywright.github.integration.enabled=true` is set.
///
/// When enabled, provide credentials through either environment variables or the
/// configured secrets provider mappings:
///
/// - `GITHUB_USER` &mdash; your GitHub username
/// - `GITHUB_API_TOKEN` &mdash; a personal access token with `repo` scope
///
/// @see <a href="https://playwright.dev/java/docs/api-testing">Playwright: API testing</a>
@UsePlaywright(TestGitHubAPI.GitHubApiOptions.class)
@EnabledIfSystemProperty(named = "playwright.github.integration.enabled", matches = "true")
public class TestGitHubAPI extends PlaywrightApiTest {
    private static final String REPO = "test-repo-2";

    /// [OptionsFactory] that configures the [APIRequestContext] for the GitHub REST API.
    ///
    /// Inherits browser settings (engine, headless, channel) from
    /// [PlaywrightApiTest#browserOptions()] and layers GitHub-specific config on top:
    /// - Base URL: `https://api.github.com`
    /// - `Accept: application/vnd.github.v3+json` per
    ///   [GitHub API guidelines](https://docs.github.com/en/rest)
    /// - `Authorization: token <PAT>` using the `GITHUB_API_TOKEN` environment variable
    public static class GitHubApiOptions implements OptionsFactory {
        @Override
        public Options getOptions() {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/vnd.github.v3+json");
            headers.put("Authorization", "token " + apiToken());

            return browserOptions()
                    .setApiRequestOptions(new APIRequest.NewContextOptions()
                            .setBaseURL("https://api.github.com")
                            .setExtraHTTPHeaders(headers));
        }
    }

    /// Creates the test repository on GitHub before all tests run.
    ///
    /// Uses `POST /user/repos` to create a repository named [#REPO].
    @BeforeAll
    void beforeAll(APIRequestContext request) {
        APIResponse newRepo =
                request.post("/user/repos", RequestOptions.create().setData(Collections.singletonMap("name", REPO)));
        assertTrue(newRepo.ok(), newRepo.text());
    }

    /// Deletes the test repository on GitHub after all tests complete.
    ///
    /// Uses `DELETE /repos/{user}/{repo}` for cleanup.
    @AfterAll
    void afterAll(APIRequestContext request) {
        APIResponse deletedRepo = request.delete("/repos/" + githubUser() + "/" + REPO);
        assertTrue(deletedRepo.ok());
    }

    /// Creates a bug report issue via the GitHub API and verifies it appears in the
    /// repository's issue list with the correct title and body.
    @Test
    void shouldCreateBugReport(APIRequestContext request) {
        Map<String, String> data = new HashMap<>();
        data.put("title", "[Bug] report 1");
        data.put("body", "Bug description");
        APIResponse newIssue = request.post(
                "/repos/" + githubUser() + "/" + REPO + "/issues",
                RequestOptions.create().setData(data));
        assertTrue(newIssue.ok());

        APIResponse issues = request.get("/repos/" + githubUser() + "/" + REPO + "/issues");
        assertTrue(issues.ok());
        JsonArray json = new Gson().fromJson(issues.text(), JsonArray.class);
        JsonObject issue = null;
        for (JsonElement item : json) {
            JsonObject itemObj = item.getAsJsonObject();
            if (!itemObj.has("title")) {
                continue;
            }
            if ("[Bug] report 1".equals(itemObj.get("title").getAsString())) {
                issue = itemObj;
                break;
            }
        }
        assertNotNull(issue);
        assertEquals("Bug description", issue.get("body").getAsString(), issue.toString());
    }

    /// Creates a feature request issue via the GitHub API and verifies it appears in the
    /// repository's issue list with the correct title and body.
    @Test
    void shouldCreateFeatureRequest(APIRequestContext request) {
        Map<String, String> data = new HashMap<>();
        data.put("title", "[Feature] request 1");
        data.put("body", "Feature description");
        APIResponse newIssue = request.post(
                "/repos/" + githubUser() + "/" + REPO + "/issues",
                RequestOptions.create().setData(data));
        assertTrue(newIssue.ok());

        APIResponse issues = request.get("/repos/" + githubUser() + "/" + REPO + "/issues");
        assertTrue(issues.ok());
        JsonArray json = new Gson().fromJson(issues.text(), JsonArray.class);
        JsonObject issue = null;
        for (JsonElement item : json) {
            JsonObject itemObj = item.getAsJsonObject();
            if (!itemObj.has("title")) {
                continue;
            }
            if ("[Feature] request 1".equals(itemObj.get("title").getAsString())) {
                issue = itemObj;
                break;
            }
        }
        assertNotNull(issue);
        assertEquals("Feature description", issue.get("body").getAsString(), issue.toString());
    }

    /// Creates a feature request issue via API, then navigates to the repository's issue
    /// page in a browser and asserts the newly created issue appears first in the list
    /// using [PlaywrightAssertions][com.microsoft.playwright.assertions.PlaywrightAssertions].
    ///
    /// This test demonstrates mixing API calls with browser-based assertions. For cookie-based
    /// authentication scenarios, see
    /// [PlaywrightApiTest#createAuthenticatedBrowserContext(APIRequestContext, Browser)][PlaywrightApiTest]
    /// which transfers API auth state to a browser context.
    @Test
    void lastCreatedIssueShouldBeFirstInTheList(APIRequestContext request, Page page) {
        Map<String, String> data = new HashMap<>();
        data.put("title", "[Feature] request 1");
        data.put("body", "Feature description");
        APIResponse newIssue = request.post(
                "/repos/" + githubUser() + "/" + REPO + "/issues",
                RequestOptions.create().setData(data));
        assertTrue(newIssue.ok());

        page.navigate("https://github.com/" + githubUser() + "/" + REPO + "/issues");
        Locator firstIssue = page.locator("a[data-hovercard-type='issue']").first();
        assertThat(firstIssue).hasText("[Feature] request 1");
    }

    private static String githubUser() {
        return SecretsProvider.require("GITHUB_USER");
    }

    private static String apiToken() {
        return SecretsProvider.require("GITHUB_API_TOKEN");
    }
}
