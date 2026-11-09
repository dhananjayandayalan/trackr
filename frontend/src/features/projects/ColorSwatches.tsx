import styles from './ColorSwatches.module.css'

/** The project colours the api accepts (ck_project_color): the avatar palette. */
export const projectColors = ['pink', 'cyan', 'lime', 'yellow', 'orange', 'violet'] as const
export type ProjectColor = (typeof projectColors)[number]

/** Colour as radios: each swatch's name is its accessible label, the colour alone would say nothing. */
export function ColorSwatches({
  value,
  onChange,
}: {
  value: ProjectColor
  onChange: (value: ProjectColor) => void
}) {
  return (
    <fieldset className={styles.root}>
      <legend className={styles.legend}>Colour</legend>
      <div className={styles.swatches}>
        {projectColors.map((color) => (
          <label key={color} className={styles.swatch} data-color={color} title={color}>
            <input
              type="radio"
              name="color"
              value={color}
              checked={value === color}
              onChange={() => {
                onChange(color)
              }}
              className={styles.input}
            />
            <span className="visually-hidden">{color}</span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}
