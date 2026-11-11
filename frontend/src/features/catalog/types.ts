export interface CatalogCourse {
  id: string;
  code: string;
  examCode: string;
  title: string;
  description: string;
  authorDisplay: string;
  unlockRule: 'STRICT' | 'COMPLETION' | 'FREE';
  chapters: number;
  lessons: number;
  shared: boolean;
}

export interface CatalogLesson {
  id: string;
  kind: 'LESSON' | 'CHAPTER_TEST' | 'MOCK_TEST';
  title: string;
}

export interface CatalogChapter {
  title: string;
  lessons: CatalogLesson[];
}

export interface CourseOutline extends Omit<CatalogCourse, 'chapters' | 'lessons'> {
  chapters: CatalogChapter[];
}
