/**
 * V3 prototype content.
 *
 * RULE FOR THIS FILE: the marketing surface carries no invented facts. Every
 * claim on the signed-out home maps to something that exists in this repo --
 * a route in App.tsx or an entry in api/endpoints.ts. There are deliberately
 * no user counts, success rates, testimonials, job-count figures, employer
 * names, or pricing, because none of those exist in the product.
 *
 * The only fixture data below is WORKBENCH_FIXTURE, used exclusively by the
 * /__design-v3/workbench-data state to check dense-layout behaviour. It is
 * shaped after the real API responses (JobResponse, JobSearchHistoryItem,
 * ResumeResponse, JobApplicationStatsResponse) and is labelled in the review
 * toolbar, never inside the page.
 */

export type Lang = 'zh' | 'en'

export const PROTOTYPE_NOTICE = 'V3 prototype · DEV route only'

/* ------------------------------------------------------------------ routes */

/** Real application routes, from frontend/src/App.tsx. */
export const ROUTES = {
  home: '/',
  jobs: '/jobs',
  login: '/login',
  register: '/register',
  dashboard: '/dashboard',
  resumes: '/resumes',
  resumeBuilder: '/resumes/builder',
  tailor: '/tailor',
  profile: '/profile',
} as const

/* --------------------------------------------------- workbench DEV fixture */

/**
 * Field-for-field subsets of real responses. Nothing here is a new field.
 *
 *   drafts  -> GET /resume-builder/drafts  (ResumeBuilderDraftSummary:
 *              id, title, templateType, updatedAt). `readyToGenerate` is not
 *              stored anywhere: it is what the frontend's own
 *              computeDraftReadiness(content, targetRole).readyToGenerate
 *              returns for the draft (name + contact + target all present).
 *   uploads -> GET /resumes                (ResumeResponse: id, fileName,
 *              fileType, updatedAt)
 *   jobs    -> GET /jobs                   (JobResponse: title, company,
 *              applied, appliedAt). "Saved" and "applied" are DERIVED from
 *              JobResponse.applied exactly as the production DashboardPage
 *              derives them. There is no saved-at field, so the saved job to
 *              continue with is the first unapplied row in API order.
 *
 * Because 职位 and 最近投递 both come from the one GET /jobs call, they can only
 * fail together; the resume center depends on the two resume calls instead.
 */
export type FixtureJob = {
  id: number
  title: string
  company: string
  applied: boolean
  appliedAt: string | null
}

export type FixtureDraft = {
  id: number
  title: string
  templateType: string
  updatedAt: string
  readyToGenerate: boolean
}

export type FixtureUpload = {
  id: number
  fileName: string
  fileType: string
  updatedAt: string
}

type WorkbenchFixture = {
  drafts: FixtureDraft[]
  uploads: FixtureUpload[]
  jobs: FixtureJob[]
  userName: string
  userFirstName: string
  userInitials: string
}

export const WORKBENCH_FIXTURE: WorkbenchFixture = {
  drafts: [
    {
      id: 3,
      title: 'Alex Morgan – Junior Software Developer',
      templateType: 'STUDENT',
      updatedAt: '2026-09-08',
      readyToGenerate: true,
    },
  ],
  uploads: [{ id: 2, fileName: 'resume-general.pdf', fileType: 'PDF', updatedAt: '2026-09-02' }],
  /* Synthetic employers drawn from the repo's existing fixture vocabulary --
     never a real company, and never repeated down a whole column. */
  jobs: [
    { id: 1, title: 'Operations Coordinator', company: 'Example Company', applied: true, appliedAt: '2026-09-10' },
    { id: 2, title: 'Program Assistant', company: 'Example University', applied: true, appliedAt: '2026-09-07' },
    { id: 3, title: 'Customer Support Lead', company: 'Example Community Centre', applied: true, appliedAt: '2026-09-05' },
    { id: 4, title: 'Junior Data Analyst', company: 'Example Company', applied: true, appliedAt: '2026-09-04' },
    { id: 5, title: 'Office Administrator', company: 'Example University', applied: true, appliedAt: '2026-09-02' },
    { id: 6, title: 'Logistics Assistant', company: 'Example Community Centre', applied: true, appliedAt: '2026-08-30' },
    { id: 7, title: 'Client Services Associate', company: 'Example Community Centre', applied: false, appliedAt: null },
    { id: 8, title: 'Facilities Coordinator', company: 'Example University', applied: false, appliedAt: null },
    { id: 9, title: 'Inventory Assistant', company: 'Example Company', applied: false, appliedAt: null },
    { id: 10, title: 'Scheduling Clerk', company: 'Example Community Centre', applied: false, appliedAt: null },
    { id: 11, title: 'Reception Lead', company: 'Example University', applied: false, appliedAt: null },
    { id: 12, title: 'Payroll Assistant', company: 'Example Company', applied: false, appliedAt: null },
  ],
  userName: 'Alex Morgan',
  userFirstName: 'Alex',
  userInitials: 'AM',
}

/** The builder draft the user touched last is the one the resume center leads with. */
export const primaryDraft = (f = WORKBENCH_FIXTURE): FixtureDraft | undefined =>
  f.drafts.slice().sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))[0]

/** The most recently updated uploaded file, shown as the secondary resume. */
export const latestUpload = (f = WORKBENCH_FIXTURE): FixtureUpload | undefined =>
  f.uploads.slice().sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))[0]

/** Derived the same way DashboardPage.tsx derives them from GET /jobs. */
export const savedJobs = (f = WORKBENCH_FIXTURE) => f.jobs.filter((j) => !j.applied)
export const appliedJobs = (f = WORKBENCH_FIXTURE) =>
  f.jobs
    .filter((j) => j.applied)
    .slice()
    .sort((a, b) => (b.appliedAt ?? '').localeCompare(a.appliedAt ?? ''))

/* -------------------------------------------------------------------- copy */

type Copy = {
  brand: string
  siteNav: { home: string; jobs: string; resumeTools: string; tailor: string }
  auth: { login: string; register: string; language: string }
  hero: {
    titleA: string
    titleEm: string
    sub: string
    primary: string
    secondary: string
    facts: string[]
  }
  /* Hero illustration: a semantic walkthrough of the one capability that
     distinguishes this product. Every string is generic guidance, never an
     invented company, role, name, percentage or score. */
  shot: {
    title: string
    status: string
    reqTitle: string
    suggestTitle: string
    beforeLabel: string
    afterLabel: string
    out1: string
    out2: string
    basedOn: string
    needsConfirm: string
  }
  caps: {
    eyebrow: string
    title: string
    sub: string
    items: { title: string; body: string; link: string }[]
  }
  steps: {
    eyebrow: string
    title: string
    sub: string
    items: { title: string; body: string }[]
  }
  cta: { title: string; sub: string; primary: string; secondary: string }
  footer: { about: string; nav: string; legal: string; rights: string }
  app: {
    /* top navigation -- the same chrome the marketing site uses */
    sidebarLabel: string
    language: string
    dashboard: string
    jobs: string
    resumeTools: string
    myResumes: string
    buildResume: string
    qualityScore: string
    tailor: string
    account: string
    profile: string
    logout: string
    openMenu: string
    closeMenu: string
    /* workbench page head */
    greeting: (first: string) => string
    dataSubtitle: string
    newSubtitle: string
    /* resume center */
    resumeCenter: string
    manageAll: string
    /** Display label for the real ResumeBuilderTemplateType value. */
    templateLabel: (templateType: string) => string
    updatedOn: (isoDate: string) => string
    readyStatus: string
    tailorByJob: string
    editResume: string
    qualityScoreLink: string
    noResumes: string
    /* jobs */
    jobsTitle: string
    savedBadge: (n: number) => string
    findJobs: string
    continueSaved: string
    savedStatus: string
    viewJob: string
    noSaved: string
    /* recent applications */
    recentApplied: string
    viewAll: string
    noApplied: string
    /* new account (unchanged) */
    startTitle: string
    startBody: string
    createResume: string
    altFindJobs: string
    /* states */
    loading: string
    reload: string
    errorTitle: string
    errorBody: string
    resumeFailed: string
    jobsFailed: string
  }
}

const zh: Copy = {
  brand: 'AI Job Assistant',
  siteNav: { home: '首页', jobs: '职位', resumeTools: '简历工具', tailor: '简历改写' },
  auth: { login: '登录', register: '注册', language: '语言' },
  hero: {
    titleA: '找职位、做简历、',
    titleEm: '按职位描述改写',
    sub: '公开搜索职位无需登录。登录后可以整理简历、记录投递，并针对某个职位描述改写你已有的经历。',
    primary: '查找职位',
    secondary: '创建简历',
    facts: ['浏览职位无需登录', '简历与投递记录可随时删除'],
  },
  shot: {
    title: '简历改写',
    status: '基于现有经历',
    reqTitle: '职位要求',
    suggestTitle: '改写建议',
    beforeLabel: '原内容',
    afterLabel: '建议版本',
    out1: '突出与职位相关的项目经历',
    out2: '保留可验证的技术信息',
    basedOn: '基于简历中的现有经历',
    needsConfirm: '需要确认',
  },
  caps: {
    eyebrow: '核心能力',
    title: '三件事，一个工作台',
    sub: '每项能力都对应产品里已有的页面，点进去就能用。',
    items: [
      {
        title: '搜索并收藏职位',
        body: '按关键词、城市、工作方式和发布时间搜索职位，收藏感兴趣的，并标记已投递。',
        link: '前往职位',
      },
      {
        title: '整理简历与质量评分',
        body: '用简历生成器从零建立一份结构化简历，或上传已有简历，再查看质量评分。',
        link: '前往简历工具',
      },
      {
        title: '按职位描述改写',
        body: '选一个职位和一份简历，逐条对齐岗位要求改写要点，改写结果需你逐条确认后才生效。',
        link: '前往简历改写',
      },
    ],
  },
  steps: {
    eyebrow: '使用流程',
    title: '三步开始',
    sub: '和登录后控制台的引导顺序一致。',
    items: [
      { title: '创建或上传简历', body: '用生成器新建，或上传一份 PDF 简历。' },
      { title: '查找并收藏职位', body: '搜索职位，把合适的收藏起来。' },
      { title: '记录投递并改写简历', body: '标记已投递，并使用简历改写优化简历。' },
    ],
  },
  cta: {
    title: '现在就可以开始',
    sub: '浏览职位不需要账号；要保存简历和投递记录时再注册。',
    primary: '注册',
    secondary: '先浏览职位',
  },
  footer: {
    about: '求职工作台：搜索职位、整理简历、按职位描述改写、记录投递。',
    nav: '导航',
    legal: 'V3 设计原型，仅在开发环境可见。',
    rights: 'AI Job Assistant',
  },
  app: {
    sidebarLabel: '主导航',
    language: '语言',
    dashboard: '控制台',
    jobs: '职位',
    resumeTools: '简历工具',
    myResumes: '我的简历',
    buildResume: '创建简历',
    qualityScore: '简历质量评分',
    tailor: '简历改写',
    account: '账户',
    profile: '个人中心',
    logout: '退出登录',
    openMenu: '打开菜单',
    closeMenu: '关闭菜单',
    greeting: (first) => `你好，${first}`,
    dataSubtitle: '继续管理你的简历和求职进度。',
    newSubtitle: '从一份简历开始你的求职准备。',
    resumeCenter: '简历中心',
    manageAll: '管理全部',
    templateLabel: (type) =>
      ({
        STUDENT: '学生模板',
        INTERNSHIP: '实习模板',
        EXPERIENCED: '经验模板',
        CAREER_CHANGE: '转行模板',
        TECHNICAL: '技术岗模板',
        GENERIC_ATS: '通用 ATS 模板',
      })[type] ?? type,
    updatedOn: (iso) => `更新于 ${Number(iso.slice(5, 7))}月${Number(iso.slice(8, 10))}日`,
    readyStatus: '内容完整，可继续针对职位优化',
    tailorByJob: '简历改写',
    editResume: '编辑简历',
    qualityScoreLink: '查看简历质量评分',
    noResumes: '还没有简历。',
    jobsTitle: '职位',
    savedBadge: (n) => `${n} 个已收藏`,
    findJobs: '查找职位',
    continueSaved: '继续处理收藏',
    savedStatus: '已收藏 · 尚未投递',
    viewJob: '查看职位',
    noSaved: '还没有收藏的职位。',
    recentApplied: '最近投递',
    viewAll: '查看全部',
    noApplied: '还没有投递记录。',
    startTitle: '开始你的求职准备',
    startBody: '先创建或上传一份简历，之后可以查找职位并根据职位描述进行改写。',
    createResume: '创建或上传简历',
    altFindJobs: '也可以先查找职位',
    loading: '加载中',
    reload: '重新加载',
    errorTitle: '无法加载工作台',
    errorBody: '这次没有取到数据，可能是网络问题或会话已过期。',
    resumeFailed: '简历暂时无法加载。',
    jobsFailed: '职位数据暂时无法加载。',
  },
}

const en: Copy = {
  brand: 'AI Job Assistant',
  siteNav: { home: 'Home', jobs: 'Jobs', resumeTools: 'Resume tools', tailor: 'Tailor' },
  auth: { login: 'Log in', register: 'Register', language: 'Language' },
  hero: {
    titleA: 'Find jobs, build a resume, ',
    titleEm: 'tailor it to the posting',
    sub: 'Search openings without an account. Sign in to organise your resume, log applications, and rewrite your existing experience against a specific job description.',
    primary: 'Find jobs',
    secondary: 'Build a resume',
    facts: ['Browsing jobs needs no account', 'Resumes and applications can be deleted anytime'],
  },
  shot: {
    title: 'Resume tailoring',
    status: 'From existing experience',
    reqTitle: 'Posting asks for',
    suggestTitle: 'Suggested rewrite',
    beforeLabel: 'Current',
    afterLabel: 'Suggested',
    out1: 'Leads with the project work the posting asks for',
    out2: 'Keeps details that can be verified',
    basedOn: 'Built from experience already in the resume',
    needsConfirm: 'Needs confirmation',
  },
  caps: {
    eyebrow: 'What it does',
    title: 'Three things, one workbench',
    sub: 'Each one maps to a page that already exists in the product.',
    items: [
      {
        title: 'Search and save jobs',
        body: 'Search by keyword, city, work mode, and posting date. Save the ones you like and mark them applied.',
        link: 'Go to jobs',
      },
      {
        title: 'Build a resume, check its score',
        body: 'Start a structured resume from scratch in the builder, or upload one you already have, then check its quality score.',
        link: 'Go to resume tools',
      },
      {
        title: 'Rewrite against a posting',
        body: 'Pick a job and a resume, align bullets to the posting requirement by requirement, and confirm each change before it applies.',
        link: 'Go to tailoring',
      },
    ],
  },
  steps: {
    eyebrow: 'How it works',
    title: 'Three steps to start',
    sub: 'The same order the signed-in dashboard walks you through.',
    items: [
      { title: 'Create or upload a resume', body: 'Start one in the builder, or upload a PDF.' },
      { title: 'Find and save jobs', body: 'Search openings and save the ones worth applying to.' },
      { title: 'Log applications and tailor', body: 'Mark a job applied, then rewrite your resume for it.' },
    ],
  },
  cta: {
    title: 'You can start right now',
    sub: 'Browsing jobs needs no account. Register when you want to save resumes and applications.',
    primary: 'Register',
    secondary: 'Browse jobs first',
  },
  footer: {
    about: 'A job-search workbench: find roles, organise your resume, tailor it to a posting, track applications.',
    nav: 'Navigation',
    legal: 'V3 design prototype, visible in development only.',
    rights: 'AI Job Assistant',
  },
  app: {
    sidebarLabel: 'Main navigation',
    language: 'Language',
    dashboard: 'Dashboard',
    jobs: 'Jobs',
    resumeTools: 'Resume tools',
    myResumes: 'My resumes',
    buildResume: 'Build a resume',
    qualityScore: 'Quality score',
    tailor: 'Tailor',
    account: 'Account',
    profile: 'Profile',
    logout: 'Log out',
    openMenu: 'Open menu',
    closeMenu: 'Close menu',
    greeting: (first) => `Hi, ${first}`,
    dataSubtitle: 'Keep your resumes and job search moving.',
    newSubtitle: 'Start with a resume.',
    resumeCenter: 'Resume center',
    manageAll: 'Manage all',
    templateLabel: (type) =>
      ({
        STUDENT: 'Student',
        INTERNSHIP: 'Internship',
        EXPERIENCED: 'Experienced',
        CAREER_CHANGE: 'Career change',
        TECHNICAL: 'Technical',
        GENERIC_ATS: 'Generic ATS',
      })[type] ?? type,
    updatedOn: (iso) =>
      `Updated ${['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][Number(iso.slice(5, 7)) - 1]} ${Number(iso.slice(8, 10))}`,
    readyStatus: 'Complete — ready to tailor to a job',
    tailorByJob: 'Resume Rewrite',
    editResume: 'Edit resume',
    qualityScoreLink: 'View quality score',
    noResumes: 'No resumes yet.',
    jobsTitle: 'Jobs',
    savedBadge: (n) => `${n} saved`,
    findJobs: 'Find jobs',
    continueSaved: 'Continue with a saved job',
    savedStatus: 'Saved · not applied yet',
    viewJob: 'View job',
    noSaved: 'No saved jobs yet.',
    recentApplied: 'Recent applications',
    viewAll: 'View all',
    noApplied: 'No applications yet.',
    startTitle: 'Get set up',
    startBody: 'Create or upload a resume first. After that you can find jobs and rewrite it against a posting.',
    createResume: 'Create or upload a resume',
    altFindJobs: 'Or find jobs first',
    loading: 'Loading',
    reload: 'Reload',
    errorTitle: 'Could not load your workbench',
    errorBody: 'Nothing came back this time. This is usually a network problem or an expired session.',
    resumeFailed: 'Resumes could not load.',
    jobsFailed: 'Jobs could not load.',
  },
}

export const COPY: Record<Lang, Copy> = { zh, en }
