import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { CodeInput } from "./CodeInput";

function Harness({ onComplete }: { onComplete: (code: string) => void }) {
  const [value, setValue] = useState("");
  return <CodeInput value={value} onChange={setValue} onComplete={onComplete} />;
}

const cells = () => screen.getAllByRole("textbox");

describe("CodeInput", () => {
  it("moves to the next cell as digits are typed and completes after the sixth", async () => {
    const onComplete = vi.fn();
    render(<Harness onComplete={onComplete} />);
    const user = userEvent.setup();
    await user.click(cells()[0]!);
    await user.keyboard("12345");
    expect(cells()[5]).toHaveFocus();
    expect(onComplete).not.toHaveBeenCalled();
    await user.keyboard("6");
    expect(onComplete).toHaveBeenCalledWith("123456");
    expect(
      cells()
        .map((c) => (c as HTMLInputElement).value)
        .join(""),
    ).toBe("123456");
  });

  it("ignores letters", async () => {
    const onComplete = vi.fn();
    render(<Harness onComplete={onComplete} />);
    const user = userEvent.setup();
    await user.click(cells()[0]!);
    await user.keyboard("a1b");
    expect(
      cells()
        .map((c) => (c as HTMLInputElement).value)
        .join(""),
    ).toBe("1");
  });

  it("accepts a pasted code with spaces, as formatted in the email", () => {
    const onComplete = vi.fn();
    render(<Harness onComplete={onComplete} />);
    fireEvent.paste(cells()[0]!, { clipboardData: { getData: () => "482 913" } });
    expect(onComplete).toHaveBeenCalledWith("482913");
  });

  it("backspace on an empty cell clears the previous digit", async () => {
    render(<Harness onComplete={vi.fn()} />);
    const user = userEvent.setup();
    await user.click(cells()[0]!);
    await user.keyboard("12");
    await user.keyboard("{Backspace}");
    expect(
      cells()
        .map((c) => (c as HTMLInputElement).value)
        .join(""),
    ).toBe("1");
    expect(cells()[1]).toHaveFocus();
  });
});
