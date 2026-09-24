import { Button } from "../../components/Button";
import type { Page } from "./adminApi";
import styles from "./Admin.module.css";

/** Page sizes offered in every admin list. */
export const PAGE_SIZES = [25, 50, 100];

export function Pagination<T>({ page, onPage, onSize }: { page: Page<T>; onPage: (p: number) => void; onSize?: (s: number) => void }) {
  const first = page.page * page.size + 1;
  const last = page.page * page.size + page.items.length;
  return (
    <nav className={styles.pagination} aria-label="Pages">
      <p role="status">{page.items.length ? `Showing ${first}–${last} of ${page.totalItems}` : `Page ${page.page + 1} is past the end`}</p>
      <div className={styles.row}>
        <Button variant="secondary" disabled={page.page === 0} onClick={() => onPage(page.page - 1)}>
          Previous
        </Button>
        <span>
          Page {page.page + 1} of {Math.max(page.totalPages, 1)}
        </span>
        <Button variant="secondary" disabled={page.page + 1 >= page.totalPages} onClick={() => onPage(page.page + 1)}>
          Next
        </Button>
        {onSize && (
          <label className={styles.inline}>
            Per page
            <select value={page.size} onChange={(e) => onSize(Number(e.target.value))}>
              {PAGE_SIZES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>
    </nav>
  );
}
