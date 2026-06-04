# Code Style & Design Guidelines

## Language Requirements
- **English only**: All comments, UI text, variable names must be in English
- Remove Chinese and Korean text from codebase
- Remove unused imports after cleanup

## Code Cleanup Rules
- **Remove Chinese AI clients**: Zhipu, Tongyi, Wenxin, Baichuan - all removed
- Delete any remaining references to removed AI clients
- Clean up after removing Chinese AI client code

## UI/UX Design Guidelines

### Theme Support (REQUIRED)
- Both light mode and dark mode must be supported
- Use CSS custom properties (CSS variables) for theming
- Test UI changes in BOTH light and dark modes

### CSS Variables Usage
```css
/* Define theme variables */
:root {
  --bg-primary: #ffffff;
  --text-primary: #000000;
  --border-color: #e0e0e0;
}

[data-theme='dark'] {
  --bg-primary: #1a1a1a;
  --text-primary: #ffffff;
  --border-color: #333333;
}

/* Use variables */
.component {
  background: var(--bg-primary);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
}
```

### Verification Checklist
- [ ] Works in light mode
- [ ] Works in dark mode
- [ ] Colors use CSS variables
- [ ] Borders use CSS variables
- [ ] Backgrounds use CSS variables

## AI Model Configuration
Supported models:
- Gemini: `gemini-3.5-flash`
- Claude: `claude-sonnet-4-6`
- OpenAI: `gpt-5.4-mini` (preferred default — gpt-5.5 also supported
  but slower with tools due to chat-completions reasoning_effort
  constraint)




