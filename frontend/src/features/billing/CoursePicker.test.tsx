import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';
import { resetI18n } from '../../i18n';
import { CoursePicker, type PickableCourse } from './CoursePicker';

const courses: PickableCourse[] = ['a', 'b', 'c'].map((x) => ({ id: `id-${x}`, code: `C${x}`, title: `Khoá ${x}`, examCode: 'IELTS' }));

function Harness({ max }: { max: number }) {
  const [v, setV] = useState<string[]>([]);
  return <CoursePicker courses={courses} max={max} value={v} onChange={setV} />;
}

beforeEach(async () => {
  await resetI18n('vi');
});

describe('CoursePicker', () => {
  it('disablesBeyondMax: once the plan limit is reached the unticked courses are disabled, and un-ticking frees them', async () => {
    render(<Harness max={2} />);
    await userEvent.click(screen.getByRole('checkbox', { name: /Khoá a/ }));
    await userEvent.click(screen.getByRole('checkbox', { name: /Khoá b/ }));
    expect(screen.getByRole('checkbox', { name: /Khoá c/ })).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: /Khoá a/ })).toBeEnabled();
    expect(screen.getByTestId('course-count')).toHaveTextContent('Đã chọn 2/2 khoá');
    await userEvent.click(screen.getByRole('checkbox', { name: /Khoá a/ }));
    expect(screen.getByRole('checkbox', { name: /Khoá c/ })).toBeEnabled();
  });
});
