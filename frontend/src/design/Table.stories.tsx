import type { Meta, StoryObj } from '@storybook/react-vite';
import { Badge } from './Badge';
import { Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from './Table';

const rows = [
  ['Q-1042', 'Trắc nghiệm một đáp án', 'Nguyễn Thu Hà', 'Chờ duyệt'],
  ['Q-1043', 'Điền vào chỗ trống', 'Trần Minh Quân', 'Cần sửa'],
  ['Q-1044', 'Ghép tiêu đề đoạn văn', 'Lê Phương Anh', 'Đã duyệt'],
];

const meta = {
  title: 'Design/Table',
  component: Table,
  args: { caption: 'Hàng việc duyệt câu hỏi', dense: true, children: null },
  render: (args) => (
    <Table {...args}>
      <TableHead>
        <TableRow>
          <TableHeaderCell onSort={() => {}} sort="ascending">Mã</TableHeaderCell>
          <TableHeaderCell>Dạng câu hỏi</TableHeaderCell>
          <TableHeaderCell>Người soạn</TableHeaderCell>
          <TableHeaderCell>Trạng thái</TableHeaderCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {rows.map(([id, kind, who, st]) => (
          <TableRow key={id}>
            <TableCell>{id}</TableCell>
            <TableCell>{kind}</TableCell>
            <TableCell>{who}</TableCell>
            <TableCell><Badge tone={st === 'Đã duyệt' ? 'success' : st === 'Cần sửa' ? 'danger' : 'warning'}>{st}</Badge></TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  ),
} satisfies Meta<typeof Table>;
export default meta;
export const Default: StoryObj<typeof meta> = {};
