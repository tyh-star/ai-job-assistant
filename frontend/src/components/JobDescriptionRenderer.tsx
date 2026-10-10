import { parseJobDescription } from '../jobCatalog/parseJobDescription'

export function JobDescriptionRenderer({
  text,
  fallbackTitle,
}: {
  text: string
  fallbackTitle: string
}) {
  const sections = parseJobDescription(text, fallbackTitle)
  if (sections.length === 0) {
    return null
  }
  return (
    <div className="min-w-0 space-y-8 overflow-hidden">
      {sections.map((section, sectionIndex) => (
        <section key={`${section.title}-${sectionIndex}`} className="min-w-0">
          <h2 className="text-lg font-semibold tracking-tight text-ink">{section.title}</h2>
          <div className="mt-3 space-y-3 text-sm leading-6 text-ink">
            {section.blocks.map((block, blockIndex) =>
              block.type === 'list' ? (
                <ul key={blockIndex} className="list-disc space-y-1 pl-5">
                  {block.items.map((item, itemIndex) => (
                    <li key={itemIndex} className="min-w-0 break-words">
                      {item}
                    </li>
                  ))}
                </ul>
              ) : (
                <p key={blockIndex} className="min-w-0 overflow-hidden break-words">
                  {block.text}
                </p>
              ),
            )}
          </div>
        </section>
      ))}
    </div>
  )
}
