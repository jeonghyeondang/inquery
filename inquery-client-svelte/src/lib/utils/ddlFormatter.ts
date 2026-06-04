/**
 * DDL Formatter & Schema Parser
 *
 * Handles BigQuery DDL with deeply nested STRUCT/ARRAY types:
 * 1. formatDDL() - Indents nested STRUCT/ARRAY for readability
 * 2. parseDDLToSchema() - Extracts field tree for BigQuery console-style schema view
 */

export interface SchemaField {
  name: string;
  type: string;
  mode: 'NULLABLE' | 'REPEATED' | 'REQUIRED';
  children?: SchemaField[];
  expanded?: boolean;
  primaryKey?: boolean;
  defaultValue?: string;
  comment?: string;
}

/**
 * Formats a BigQuery DDL string with proper indentation for nested types.
 */
export function formatDDL(ddl: string): string {
  if (!ddl || !ddl.trim()) return ddl;

  const lines = ddl.split('\n');
  const result: string[] = [];

  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) {
      result.push('');
      continue;
    }

    if (trimmed.startsWith('CREATE ') || trimmed === '(' || trimmed === ')' || trimmed === ');') {
      result.push(trimmed);
      continue;
    }

    const fieldMatch = trimmed.match(/^(\w+)\s+(.+?)([,]?)$/);
    if (!fieldMatch) {
      result.push('  ' + trimmed);
      continue;
    }

    const [, fieldName, typeExpr, comma] = fieldMatch;
    const formatted = formatTypeExpression(fieldName, typeExpr, comma, 1);
    result.push(formatted);
  }

  return result.join('\n');
}

function formatTypeExpression(name: string, typeExpr: string, comma: string, depth: number): string {
  const indent = '  '.repeat(depth);
  const upper = typeExpr.toUpperCase().trim();

  if (!upper.includes('STRUCT<') && !upper.includes('ARRAY<')) {
    return `${indent}${name} ${typeExpr}${comma}`;
  }

  const fields = parseInlineFields(typeExpr);
  if (!fields) {
    return `${indent}${name} ${typeExpr}${comma}`;
  }

  const lines: string[] = [];
  const wrapper = getTypeWrapper(typeExpr);
  lines.push(`${indent}${name} ${wrapper.open}`);

  for (let i = 0; i < fields.length; i++) {
    const field = fields[i];
    const fieldComma = i < fields.length - 1 ? ',' : '';
    const innerUpper = field.type.toUpperCase();

    if (innerUpper.includes('STRUCT<') || innerUpper.includes('ARRAY<')) {
      const nested = formatTypeExpression(field.name, field.type, fieldComma, depth + 1);
      lines.push(nested);
    } else {
      lines.push(`${'  '.repeat(depth + 1)}${field.name} ${field.type}${fieldComma}`);
    }
  }

  lines.push(`${indent}${wrapper.close}${comma}`);
  return lines.join('\n');
}

function getTypeWrapper(typeExpr: string): { open: string; close: string } {
  const upper = typeExpr.toUpperCase().trim();
  if (upper.startsWith('ARRAY<STRUCT<')) {
    return { open: 'ARRAY<STRUCT<', close: '>>' };
  }
  if (upper.startsWith('STRUCT<')) {
    return { open: 'STRUCT<', close: '>' };
  }
  if (upper.startsWith('ARRAY<')) {
    return { open: 'ARRAY<', close: '>' };
  }
  return { open: typeExpr, close: '' };
}

interface InlineField {
  name: string;
  type: string;
}

function parseInlineFields(typeExpr: string): InlineField[] | null {
  let content = typeExpr.trim();
  if (content.toUpperCase().startsWith('ARRAY<')) {
    content = content.slice(6, content.length - 1).trim();
  }
  if (!content.toUpperCase().startsWith('STRUCT<')) return null;

  const startIdx = content.indexOf('<') + 1;
  let depth = 1;
  let endIdx = startIdx;
  for (let i = startIdx; i < content.length && depth > 0; i++) {
    if (content[i] === '<') depth++;
    else if (content[i] === '>') depth--;
    if (depth === 0) endIdx = i;
  }

  const fieldsStr = content.substring(startIdx, endIdx);
  const fields: InlineField[] = [];
  depth = 0;
  let current = '';

  for (let i = 0; i < fieldsStr.length; i++) {
    const ch = fieldsStr[i];
    if (ch === '<') depth++;
    else if (ch === '>') depth--;
    else if (ch === ',' && depth === 0) {
      pushField(current.trim(), fields);
      current = '';
      continue;
    }
    current += ch;
  }
  pushField(current.trim(), fields);

  return fields.length > 0 ? fields : null;
}

function pushField(raw: string, fields: InlineField[]) {
  if (!raw) return;
  const spaceIdx = raw.indexOf(' ');
  if (spaceIdx > 0) {
    fields.push({ name: raw.substring(0, spaceIdx), type: raw.substring(spaceIdx + 1).trim() });
  }
}

/**
 * Parses BigQuery DDL into a list of SchemaField for the schema table view.
 */
export function parseDDLToSchema(ddl: string): SchemaField[] {
  if (!ddl || !ddl.trim()) return [];

  const columnsBlock = extractColumnsBlock(ddl);
  if (!columnsBlock) return [];

  const topFields = splitTopLevelFields(columnsBlock);
  return topFields.map(parseFieldDefinition);
}

function extractColumnsBlock(ddl: string): string | null {
  const parenStart = ddl.indexOf('(');
  if (parenStart === -1) return null;

  let depth = 0;
  let endIdx = -1;
  for (let i = parenStart; i < ddl.length; i++) {
    if (ddl[i] === '(') depth++;
    else if (ddl[i] === ')') {
      depth--;
      if (depth === 0) { endIdx = i; break; }
    }
  }

  if (endIdx === -1) return null;
  return ddl.substring(parenStart + 1, endIdx).trim();
}

function splitTopLevelFields(block: string): string[] {
  const fields: string[] = [];
  let depth = 0;
  let current = '';

  for (let i = 0; i < block.length; i++) {
    const ch = block[i];
    if (ch === '<' || ch === '(') depth++;
    else if (ch === '>' || ch === ')') depth--;
    else if (ch === ',' && depth === 0) {
      const trimmed = current.trim();
      if (trimmed) fields.push(trimmed);
      current = '';
      continue;
    }
    current += ch;
  }
  const last = current.trim();
  if (last) fields.push(last);
  return fields;
}

function parseFieldDefinition(fieldStr: string): SchemaField {
  const trimmed = fieldStr.trim();
  const spaceIdx = trimmed.indexOf(' ');
  if (spaceIdx <= 0) {
    return { name: trimmed, type: 'UNKNOWN', mode: 'NULLABLE' };
  }

  const name = trimmed.substring(0, spaceIdx);
  const typeExpr = trimmed.substring(spaceIdx + 1).trim();
  const upper = typeExpr.toUpperCase();

  let mode: SchemaField['mode'] = 'NULLABLE';
  let displayType = typeExpr;
  let children: SchemaField[] | undefined;

  if (upper.startsWith('ARRAY<STRUCT<')) {
    mode = 'REPEATED';
    displayType = 'RECORD';
    children = parseStructChildren(typeExpr);
  } else if (upper.startsWith('ARRAY<')) {
    mode = 'REPEATED';
    const inner = typeExpr.slice(6, typeExpr.length - 1).trim();
    displayType = inner.toUpperCase();
  } else if (upper.startsWith('STRUCT<')) {
    mode = 'NULLABLE';
    displayType = 'RECORD';
    children = parseStructChildren(typeExpr);
  } else {
    displayType = mapBQType(upper);
  }

  return { name, type: displayType, mode, children };
}

function parseStructChildren(typeExpr: string): SchemaField[] {
  let content = typeExpr.trim();
  if (content.toUpperCase().startsWith('ARRAY<')) {
    content = content.slice(6, content.length - 1).trim();
  }
  if (!content.toUpperCase().startsWith('STRUCT<')) return [];

  const startIdx = content.indexOf('<') + 1;
  let depth = 1;
  let endIdx = startIdx;
  for (let i = startIdx; i < content.length && depth > 0; i++) {
    if (content[i] === '<') depth++;
    else if (content[i] === '>') depth--;
    if (depth === 0) endIdx = i;
  }

  const fieldsStr = content.substring(startIdx, endIdx);
  const fieldStrs = splitTopLevelFields(fieldsStr);
  return fieldStrs.map(parseFieldDefinition);
}

function mapBQType(upper: string): string {
  if (upper === 'INT64') return 'INTEGER';
  if (upper === 'FLOAT64') return 'FLOAT';
  if (upper === 'BOOL') return 'BOOLEAN';
  if (upper === 'BYTES') return 'BYTES';
  return upper;
}
