package oscarvarto.mx;

import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.junit.UsePlaywright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.KeyboardModifier;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.SelectOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@UsePlaywright
public class ActionsGuideTest {
    @Test
    void shouldClickButton(Page page) {
        page.navigate("data:text/html,<script>var result;</script><button onclick='result=\"Clicked\"'>Go</button>");
        page.locator("button").click();
        assertEquals("Clicked", page.evaluate("result"));
    }

    @Test
    void shouldCheckTheBox(Page page) {
        page.setContent("<input id='checkbox' type='checkbox'></input>");
        page.locator("input").check();
        assertEquals(true, page.evaluate("window['checkbox'].checked"));
    }

    @Test
    void shouldSearchWiki(Page page) {
        page.navigate("https://www.wikipedia.org/");
        page.locator("input[name=\"search\"]").click();
        page.locator("input[name=\"search\"]").fill("playwright");
        page.locator("input[name=\"search\"]").press("Enter");
        assertThat(page).hasURL("https://en.wikipedia.org/wiki/Playwright");
    }

    // == https://playwright.dev/java/docs/input ==

    // https://playwright.dev/java/docs/input#text-input
    @Disabled("Syntax example only")
    @Test
    void textInputTest(Page page) {
        // Text input
        page.getByRole(AriaRole.TEXTBOX).fill("Peter");

        // Date input
        page.getByLabel("Birth date").fill("2020-02-02");

        // Time input
        page.getByLabel("Appointment time").fill("13:15");

        // Local datetime input
        page.getByLabel("Local time").fill("2020-03-02T05:15");
    }

    // https://playwright.dev/java/docs/input#checkboxes-and-radio-buttons
    @Disabled("Syntax example only")
    @Test
    void checkBoxesAndRadioButtonsTest(Page page) {
        // Check the checkbox
        page.getByLabel("I agree to the terms above").check();

        // Assert the checked state
        assertThat(page.getByLabel("Subscribe to newsletter")).isChecked();

        // Select the radio button
        page.getByLabel("XL").check();
    }

    // https://playwright.dev/java/docs/input#select-options
    @Disabled("Syntax example only")
    @Test
    void selectOptionsTest(Page page) {
        // Single selection matching the value or label
        page.getByLabel("Choose a color").selectOption("blue");

        // Single selection matching the label
        page.getByLabel("Choose a color").selectOption(new SelectOption().setLabel("Blue"));

        // Multiple selected items
        page.getByLabel("Choose multiple colors").selectOption(new String[] {"red", "green", "blue"});
    }

    // https://playwright.dev/java/docs/input#mouse-click
    @Disabled("Syntax example only")
    @Test
    void mouseClickTest(Page page) {
        // Generic click
        page.getByRole(AriaRole.BUTTON).click();

        // Double click
        page.getByText("Item").dblclick();

        // Right click
        page.getByText("Item").click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));

        // Shift + click
        page.getByText("Item").click(new Locator.ClickOptions().setModifiers(Arrays.asList(KeyboardModifier.SHIFT)));

        // Ctrl + click on Windows and Linux
        // Meta + click on macOS
        page.getByText("Item")
                .click(new Locator.ClickOptions().setModifiers(Arrays.asList(KeyboardModifier.CONTROLORMETA)));

        // Hover over element
        page.getByText("Item").hover();

        // Click the top left corner
        page.getByText("Item").click(new Locator.ClickOptions().setPosition(0, 0));
    }

    // Caution: Most of the time, you should input text with Locator.fill(). See the Text input section above. You only
    // need to type characters if there is special keyboard handling on the page.
    // https://playwright.dev/java/docs/input#type-characters
    @Disabled("Syntax example only")
    @Test
    void typeCharactersOnlyIfSpecialKeyboardHandlingTest(Page page) {
        // Press keys one by one
        page.locator("#area").pressSequentially("Hello World!");
    }

    // https://playwright.dev/java/docs/input#keys-and-shortcuts

    // The Locator.press() method focuses the selected element and produces a single keystroke. It accepts the logical
    // key names that are emitted in the keyboardEvent.key property of the keyboard events:
    // Backquote, Minus, Equal, Backslash, Backspace, Tab, Delete, Escape,
    // ArrowDown, End, Enter, Home, Insert, PageDown, PageUp, ArrowRight,
    // ArrowUp, F1 - F12, Digit0 - Digit9, KeyA - KeyZ, etc.
    //
    // Following modification shortcuts are also supported: Shift, Control, Alt, Meta
    @Disabled("Syntax example only")
    @Test
    void keysAndShortcuts(Page page) {
        // Hit Enter
        page.getByText("Submit").press("Enter");

        // Dispatch Control+Right
        page.getByRole(AriaRole.TEXTBOX).press("Control+ArrowRight");

        // Press $ sign on keyboard
        page.getByRole(AriaRole.TEXTBOX).press("$");
    }

    // https://playwright.dev/java/docs/input#upload-files
    //
    // You can select input files for upload using the Locator.setInputFiles() method. It expects first argument to
    // point
    // to an input element with the type "file". Multiple files can be passed in the array. If some of the file paths
    // are
    // relative, they are resolved relative to the current working directory. Empty array clears the selected files.
    @Disabled("Syntax example only")
    @Test
    void uploadFilesTest(Page page) {
        // Select one file
        page.getByLabel("Upload file").setInputFiles(Paths.get("myfile.pdf"));

        // Select multiple files
        page.getByLabel("Upload files").setInputFiles(new Path[] {Paths.get("file1.txt"), Paths.get("file2.txt")});

        // Select a directory
        page.getByLabel("Upload directory").setInputFiles(Paths.get("mydir"));

        // Remove all the selected files
        page.getByLabel("Upload file").setInputFiles(new Path[0]);

        // Upload buffer from memory
        page.getByLabel("Upload file")
                .setInputFiles(
                        new FilePayload("file.txt", "text/plain", "this is test".getBytes(StandardCharsets.UTF_8)));

        // If you don't have input element in hand (it is created dynamically), you can handle the
        // Page.onFileChooser(handler) event or use a corresponding waiting method upon your action:
        FileChooser fileChooser = page.waitForFileChooser(() -> {
            page.getByLabel("Upload file").click();
        });
        fileChooser.setFiles(Paths.get("myfile.pdf"));
    }

    @Disabled("Syntax example only")
    @Test
    void severalSmallActionsTest(Page page) {
        // https://playwright.dev/java/docs/input#focus-element
        // For the dynamic pages that handle focus events, you can focus the given element with Locator.focus().
        page.getByLabel("Password").focus();

        // https://playwright.dev/java/docs/input#drag-and-drop
        // Drag and drop
        page.locator("#item-to-be-dragged").dragTo(page.locator("#item-to-drop-at"));

        // https://playwright.dev/java/docs/input#dragging-manually
        // Drag and drop manually
        page.locator("#item-to-be-dragged").hover();
        page.mouse().down();
        page.locator("#item-to-drop-at").hover();
        page.mouse().up();
    }

    // Scrolling
    // https://playwright.dev/java/docs/input#scrolling
    // Most of the time, Playwright will automatically scroll for you before doing any actions. Therefore, you do not
    // need to scroll explicitly.
    @Disabled("Syntax example only")
    @Test
    void scrollingTest(Page page) {
        // Scrolls automatically so that button is visible
        page.getByRole(AriaRole.BUTTON).click();

        // **Manually scrolling**: only when necessary

        // Scroll the footer into view, forcing an "infinite list" to load more content
        page.getByText("Footer text").scrollIntoViewIfNeeded();

        // Position the mouse and scroll with the mouse wheel
        page.getByTestId("scrolling-container").hover();
        // page.mouse.wheel(0, 10); <-- Probably docs are outdated

        // Alternatively, programmatically scroll a specific element
        page.getByTestId("scrolling-container").evaluate("e => e.scrollTop += 100");
    }
}
