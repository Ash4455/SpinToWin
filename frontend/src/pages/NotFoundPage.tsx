import { Link } from 'react-router';

export function NotFoundPage() {
  return (
    <section>
      <h1>找不到頁面</h1>
      <Link to="/">回首頁</Link>
    </section>
  );
}
