import type { ReactNode } from "react";
import { Link } from "./Link";

interface Props {
  id: string;
  title?: string;
  meta?: string;
  action?: { label: string; to: string };
  hideHeader?: boolean;
  flush?: boolean;
  level?: 2 | 3;
  children: ReactNode;
}

/* 구획은 제목 하나와 여백이다. 선도 배경도 두르지 않는다. */
export function Panel({ id, title, meta, action, hideHeader = false, level = 2, children }: Props) {
  const Heading = level === 3 ? "h3" : "h2";
  return (
    <section className="panel" aria-labelledby={id}>
      {!hideHeader && (
        <div className="panel-head">
          <Heading id={id} className="panel-title">
            {title}
          </Heading>
          {meta && <span className="panel-meta num">{meta}</span>}
          {action && (
            <Link className="panel-action" to={action.to}>
              {action.label}
            </Link>
          )}
        </div>
      )}
      {children}
    </section>
  );
}
