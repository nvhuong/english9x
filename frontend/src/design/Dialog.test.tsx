import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Button } from './Button';
import { Dialog } from './Dialog';

describe('Dialog', () => {
  const ui = (
    <Dialog trigger={<Button>Mở</Button>} title="Xoá bài?" description="Không hoàn tác được." footer={<Button>Đồng ý</Button>}>
      <p>nội dung</p>
    </Dialog>
  );

  it('opens as a named, described modal; Escape closes and focus returns to the trigger', async () => {
    const user = userEvent.setup();
    render(ui);
    const trigger = screen.getByRole('button', { name: 'Mở' });
    await user.click(trigger);
    const dlg = await screen.findByRole('dialog', { name: 'Xoá bài?' });
    expect(dlg).toHaveAccessibleDescription('Không hoàn tác được.');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('traps focus inside and has a labelled close button', async () => {
    const user = userEvent.setup();
    render(ui);
    await user.click(screen.getByRole('button', { name: 'Mở' }));
    const dlg = await screen.findByRole('dialog');
    for (let i = 0; i < 6; i++) {
      await user.tab();
      expect(dlg).toContainElement(document.activeElement as HTMLElement);
    }
    await user.click(screen.getByRole('button', { name: 'Đóng' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
