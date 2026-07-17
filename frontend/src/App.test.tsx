import { render, screen } from "@testing-library/react";
import { DeveloperDetails } from "./App";

describe("developer response separation", () => {
  it("renders diagnostics only when the developer component is mounted", () => {
    const { unmount } = render(<div aria-label="consumer-view">Photo library</div>);
    expect(screen.queryByTestId("developer-diagnostics")).not.toBeInTheDocument();
    expect(screen.queryByText("Model profile")).not.toBeInTheDocument();
    unmount();

    render(
      <DeveloperDetails
        status={{
          environment: "test",
          database: "sqlite-local",
          queue_mode: "eager-local",
          pipeline_version: "foundation-v1",
          model_profile: "test-profile",
        }}
      />,
    );
    expect(screen.getByTestId("developer-diagnostics")).toBeInTheDocument();
    expect(screen.getByText("Model profile")).toBeInTheDocument();
  });
});

