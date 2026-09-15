/**
 * Visual reference for the signed-in /dashboard. Not mounted by App.
 * Production DashboardPage must not import demoDashboardData.
 */
import type { ReactNode, SVGProps } from 'react';

export type SavedJob = {
  title: string;
  company: string;
  status: string;
};

export type Application = {
  date: string;
  title: string;
  company: string;
};

export type ResumeSummary = {
  owner: string;
  title: string;
  meta: string;
  complete?: boolean;
};

export type SecondaryResume = {
  name: string;
  meta: string;
};

export type DashboardData = {
  userName: string;
  displayName: string;
  initials: string;
  primaryResume: ResumeSummary;
  secondaryResume?: SecondaryResume;
  savedCount: number;
  savedJobs: SavedJob[];
  applications: Application[];
};

export const demoDashboardData: DashboardData = {
  userName: 'Alex',
  displayName: 'Alex Morgan',
  initials: 'AM',
  primaryResume: {
    owner: 'Alex Morgan',
    title: 'Alex Morgan – Junior Software Developer',
    meta: '学生模板 · 更新于 9月8日',
    complete: true,
  },
  secondaryResume: {
    name: 'resume-general.pdf',
    meta: 'PDF · 更新于 9月2日',
  },
  savedCount: 6,
  savedJobs: [
    {
      title: 'Client Services Associate',
      company: 'Example Community Centre',
      status: '已收藏 · 尚未投递',
    },
  ],
  applications: [
    { date: '09/10', title: 'Operations Coordinator', company: 'Example Company' },
    { date: '09/07', title: 'Program Assistant', company: 'Example University' },
    { date: '09/05', title: 'Customer Support Lead', company: 'Example Community Centre' },
  ],
};

function SvgIcon(props: SVGProps<SVGSVGElement>) {
  return <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" {...props} />;
}

function LogoIcon() {
  return (
    <SvgIcon className="td-logoIcon">
      <path d="M7.7 7.2V5.9c0-1 .8-1.9 1.9-1.9h4.8c1 0 1.9.8 1.9 1.9v1.3" />
      <path d="M5.1 8.1h13.8c.9 0 1.6.7 1.6 1.6v8.2c0 1-.8 1.8-1.8 1.8H5.3c-1 0-1.8-.8-1.8-1.8V9.7c0-.9.7-1.6 1.6-1.6Z" />
      <path d="M3.7 12.3c2.7 1.2 5.5 1.8 8.3 1.8s5.6-.6 8.3-1.8M10.4 12.8h3.2v2.3h-3.2z" />
    </SvgIcon>
  );
}

function BriefcaseIcon() {
  return (
    <SvgIcon>
      <path className="td-iconFill" d="M5.6 8.2h12.8a2 2 0 0 1 2 2v7.3a2 2 0 0 1-2 2H5.6a2 2 0 0 1-2-2v-7.3a2 2 0 0 1 2-2Z" />
      <path d="M8.3 8.1V6.6c0-1 .8-1.7 1.7-1.7h4c.9 0 1.7.8 1.7 1.7v1.5M3.8 12.6h16.4M10.3 12.5v2h3.4v-2" />
    </SvgIcon>
  );
}

function ClockIcon() {
  return (
    <SvgIcon>
      <circle className="td-iconFill" cx="12" cy="12" r="8.5" />
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.7v4.8l3.2 1.8" />
      <circle className="td-iconDot" cx="12" cy="12" r="1.1" />
    </SvgIcon>
  );
}

function FileIcon() {
  return (
    <SvgIcon>
      <path className="td-iconFill" d="M6.5 3.8h7.2l4 4v12.4H6.5z" />
      <path d="M6.5 3.8h7.2l4 4v12.4H6.5zM13.7 3.8v4h4M9.3 12h5.4M9.3 15.3h4.2" />
    </SvgIcon>
  );
}

function SearchIcon() {
  return (
    <SvgIcon>
      <circle cx="10.6" cy="10.6" r="6.2" />
      <path d="m15.2 15.2 4.4 4.4" />
    </SvgIcon>
  );
}

function ChevronDown() {
  return (
    <SvgIcon className="td-chevron">
      <path d="m8 10 4 4 4-4" />
    </SvgIcon>
  );
}

function Arrow() {
  return <span aria-hidden="true" className="td-arrow">→</span>;
}

function IconTile({ children }: { children: ReactNode }) {
  return <span className="td-iconTile">{children}</span>;
}

function ResumePreview({ owner }: { owner: string }) {
  return (
    <div className="td-resumePreview" aria-hidden="true">
      <span className="td-resumeFold" />
      <strong>{owner}</strong>
      <div className="td-previewCols">
        <span className="td-previewLine td-previewLine--short" />
        <span className="td-previewLine td-previewLine--tiny" />
      </div>
      <div className="td-previewCols">
        <span className="td-previewLine td-previewLine--short" />
        <span className="td-previewLine td-previewLine--tiny" />
      </div>
      <div className="td-previewCols">
        <span className="td-previewLine td-previewLine--short" />
        <span className="td-previewLine td-previewLine--tiny" />
      </div>
      <span className="td-previewLine" />
      <span className="td-previewLine td-previewLine--wide" />
      <span className="td-previewLine td-previewLine--medium" />
      <span className="td-previewLine td-previewLine--wide" />
      <span className="td-previewLine td-previewLine--medium" />
      <span className="td-previewLine td-previewLine--tiny" />
    </div>
  );
}

function Header({ initials, displayName }: Pick<DashboardData, 'initials' | 'displayName'>) {
  return (
    <header className="td-header">
      <div className="td-nav">
        <a className="td-brand" href="#dashboard" aria-label="AI Job Assistant">
          <span className="td-logo"><LogoIcon /></span>
          <span>AI Job Assistant</span>
        </a>
        <nav className="td-links" aria-label="主导航">
          <a className="td-link td-link--active" href="#dashboard">控制台</a>
          <a className="td-link" href="#jobs">职位</a>
          <a className="td-link td-link--tool" href="#resume-tools">简历工具 <ChevronDown /></a>
          <a className="td-link" href="#tailor">简历改写</a>
        </nav>
        <div className="td-actions">
          <div className="td-lang"><button className="is-active">中文</button><button>EN</button></div>
          <button className="td-profile">
            <span className="td-avatar">{initials}</span>
            <span>{displayName}</span>
            <ChevronDown />
          </button>
        </div>
      </div>
    </header>
  );
}

function ResumeCenter({ primaryResume, secondaryResume }: Pick<DashboardData, 'primaryResume' | 'secondaryResume'>) {
  return (
    <section className="td-card td-resumeCard">
      <div className="td-cardHeading">
        <h2>简历中心</h2>
        <a href="#all-resumes">管理全部 <Arrow /></a>
      </div>
      <div className="td-primaryResume">
        <ResumePreview owner={primaryResume.owner} />
        <div className="td-resumeInfo">
          <h3>{primaryResume.title}</h3>
          <p className="td-muted td-resumeMeta">{primaryResume.meta}</p>
          {primaryResume.complete && (
            <p className="td-complete"><span className="td-check">✓</span>内容完整，可继续针对职位优化</p>
          )}
          <div className="td-buttons">
            <a className="td-button td-button--primary" href="#tailor">按职位改写</a>
            <a className="td-button td-button--secondary" href="#edit">编辑简历</a>
          </div>
          <a className="td-quality" href="#quality">查看简历质量评分</a>
        </div>
      </div>
      <div className="td-divider" />
      {secondaryResume && (
        <a className="td-secondaryResume" href="#secondary-resume">
          <IconTile><FileIcon /></IconTile>
          <span><strong>{secondaryResume.name}</strong><small>{secondaryResume.meta}</small></span>
        </a>
      )}
    </section>
  );
}

function JobsCard({ savedCount, savedJobs }: Pick<DashboardData, 'savedCount' | 'savedJobs'>) {
  const job = savedJobs[0];
  return (
    <section className="td-card td-jobsCard">
      <div className="td-rightHeader">
        <div className="td-titleWithIcon">
          <IconTile><BriefcaseIcon /></IconTile>
          <h2>职位</h2>
          <span className="td-pill">{savedCount} 个已收藏</span>
        </div>
        <a className="td-searchButton" href="#find-jobs"><SearchIcon />查找职位</a>
      </div>
      {job ? (
        <div className="td-jobArea">
          <p className="td-muted">继续处理收藏</p>
          <div className="td-jobRow">
            <span className="td-jobMark" />
            <div className="td-jobText">
              <strong>{job.title}</strong>
              <span>{job.company}</span>
              <small>{job.status}</small>
            </div>
            <a href="#job">查看职位</a>
          </div>
        </div>
      ) : <p className="td-empty">还没有收藏的职位。</p>}
    </section>
  );
}

function ApplicationsCard({ applications }: Pick<DashboardData, 'applications'>) {
  return (
    <section className="td-card td-applicationsCard">
      <div className="td-rightHeader td-historyHeader">
        <div className="td-titleWithIcon"><IconTile><ClockIcon /></IconTile><h2>最近投递</h2></div>
        <a className="td-viewAll" href="#applications">查看全部 <Arrow /></a>
      </div>
      {applications.length ? (
        <div className="td-timeline">
          {applications.slice(0, 3).map((application, index) => (
            <div className="td-application" key={`${application.date}-${application.title}`}>
              <div className="td-dateColumn">
                <time>{application.date}</time>
                {index < applications.slice(0, 3).length - 1 && <span className="td-rail"><i /></span>}
              </div>
              <div className="td-applicationText">
                <strong>{application.title}</strong>
                <span>{application.company}</span>
              </div>
            </div>
          ))}
        </div>
      ) : <p className="td-empty">还没有投递记录。</p>}
    </section>
  );
}

export function TargetDashboard({ data }: { data: DashboardData }) {
  return (
    <div className="td-page">
      <Header initials={data.initials} displayName={data.displayName} />
      <main className="td-shell" id="dashboard">
        <section className="td-greeting">
          <h1>你好，{data.userName}</h1>
          <span className="td-greetingRule" />
          <p>继续管理你的简历和求职进度。</p>
        </section>
        <div className="td-grid">
          <ResumeCenter primaryResume={data.primaryResume} secondaryResume={data.secondaryResume} />
          <div className="td-rightColumn">
            <JobsCard savedCount={data.savedCount} savedJobs={data.savedJobs} />
            <ApplicationsCard applications={data.applications} />
          </div>
        </div>
      </main>
    </div>
  );
}
