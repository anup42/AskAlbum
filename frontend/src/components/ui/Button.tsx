import type { ButtonHTMLAttributes } from "react";
import { cn } from "../../lib/cn";

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "ghost" | "danger";
  size?: "sm" | "md" | "icon";
};

export function Button({ className, variant = "secondary", size = "md", ...props }: Props) {
  return (
    <button
      className={cn(
        "button",
        `button--${variant}`,
        `button--${size}`,
        className,
      )}
      {...props}
    />
  );
}

