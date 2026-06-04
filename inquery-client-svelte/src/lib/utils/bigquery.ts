/**
 * BigQuery Nested Data Parser
 *
 * BigQuery JDBC returns ARRAY<STRUCT> as JSON with special format:
 * - "v": value (array or scalar)
 * - "f": fields (STRUCT fields)
 *
 * Example:
 * {"v":[{"v":{"f":[{"v":"key_name"},{"v":{"f":[{"v":null},{"v":"1"},{"v":null},{"v":null}]}}]}}]}
 */

export interface BigQueryValue {
  v: any;
  f?: BigQueryValue[];
}

export interface ParsedNestedValue {
  key: string;
  value: {
    string_value: string | null;
    int_value: string | null;
    float_value: string | null;
    double_value: string | null;
  };
}

export interface FlattenedRow {
  _originalRowIndex: number;
  _nestedIndex: number;
  _rowSpan: number;
  _isFirstOfGroup: boolean;
  [key: string]: any;
}

export interface NestedColumnInfo {
  parentColumn: string;
  childColumns: string[];
  isArray: boolean;
}

export const isBigQueryNestedJson = (value: any): boolean => {
  if (typeof value !== 'string') return false;
  try {
    const parsed = JSON.parse(value);
    return parsed !== null && typeof parsed === 'object' && ('v' in parsed || 'f' in parsed);
  } catch {
    return false;
  }
};

export const parseBigQueryValue = (value: any): any => {
  if (value === null || value === undefined) return null;

  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      if (parsed && typeof parsed === 'object' && ('v' in parsed || 'f' in parsed)) {
        return extractBigQueryValue(parsed);
      }
      return value;
    } catch {
      return value;
    }
  }

  if (typeof value === 'object' && ('v' in value || 'f' in value)) {
    return extractBigQueryValue(value);
  }

  return value;
};

const extractBigQueryValue = (obj: BigQueryValue): any => {
  if (obj === null || obj === undefined) return null;

  if (obj.f && Array.isArray(obj.f)) {
    return obj.f.map((field) => extractBigQueryValue(field));
  }

  if ('v' in obj) {
    const v = obj.v;
    if (v === null || v === undefined) return null;
    if (Array.isArray(v)) return v.map((item) => extractBigQueryValue(item));
    if (typeof v === 'object' && v !== null && 'f' in v) return extractBigQueryValue(v);
    if (typeof v === 'object' && v !== null && 'v' in v) return extractBigQueryValue(v);
    return v;
  }

  return obj;
};

export const parseEventParams = (jsonStr: string): ParsedNestedValue[] => {
  try {
    const parsed = JSON.parse(jsonStr);
    const array = extractBigQueryValue(parsed);
    if (!Array.isArray(array)) return [];

    return array
      .map((item: any) => {
        if (Array.isArray(item) && item.length >= 2) {
          const key = item[0];
          const valueStruct = item[1];
          if (Array.isArray(valueStruct) && valueStruct.length >= 4) {
            return {
              key,
              value: {
                string_value: valueStruct[0],
                int_value: valueStruct[1],
                float_value: valueStruct[2],
                double_value: valueStruct[3],
              },
            };
          }
        }
        return null;
      })
      .filter(Boolean) as ParsedNestedValue[];
  } catch {
    return [];
  }
};

export const parseStructColumn = (jsonStr: string, fieldNames: string[]): Record<string, any> => {
  try {
    const parsed = JSON.parse(jsonStr);
    const values = extractBigQueryValue(parsed);
    if (!Array.isArray(values)) return {};
    const flat = flattenStructValues(values);
    const result: Record<string, any> = {};
    fieldNames.forEach((name, idx) => {
      result[name] = flat[idx] ?? null;
    });
    return result;
  } catch {
    return {};
  }
};

function flattenStructValues(values: any[]): any[] {
  const result: any[] = [];
  for (const val of values) {
    if (Array.isArray(val)) {
      result.push(...flattenStructValues(val));
    } else {
      result.push(val);
    }
  }
  return result;
}

const KNOWN_NESTED_SCHEMAS: Record<string, string[]> = {
  event_params: ['key', 'value.string_value', 'value.int_value', 'value.float_value', 'value.double_value'],
  user_properties: ['key', 'value.string_value', 'value.int_value', 'value.float_value', 'value.double_value', 'value.set_timestamp_micros'],
  device: ['category', 'mobile_brand_name', 'mobile_model_name', 'mobile_marketing_name', 'mobile_os_hardware_model', 'operating_system', 'operating_system_version', 'vendor_id', 'advertising_id', 'language', 'is_limited_ad_tracking', 'time_zone_offset_seconds', 'browser', 'browser_version', 'web_info.browser', 'web_info.browser_version', 'web_info.hostname'],
  geo: ['city', 'country', 'continent', 'region', 'sub_continent', 'metro'],
  traffic_source: ['name', 'medium', 'source'],
  app_info: ['id', 'version', 'install_store', 'firebase_app_id', 'install_source'],
  collected_traffic_source: ['manual_campaign_id', 'manual_campaign_name', 'manual_source', 'manual_medium', 'manual_term', 'manual_content', 'gclid', 'dclid', 'srsltid'],
  privacy_info: ['analytics_storage', 'ads_storage', 'uses_transient_token'],
  ecommerce: ['total_item_quantity', 'purchase_revenue_in_usd', 'purchase_revenue', 'refund_value_in_usd', 'refund_value', 'shipping_value_in_usd', 'shipping_value', 'tax_value_in_usd', 'tax_value', 'unique_items', 'transaction_id'],
  items: ['item_id', 'item_name', 'item_brand', 'item_variant', 'item_category', 'item_category2', 'item_category3', 'item_category4', 'item_category5', 'price_in_usd', 'price', 'quantity', 'item_revenue_in_usd', 'item_revenue', 'item_refund_in_usd', 'item_refund', 'coupon', 'affiliation', 'location_id', 'item_list_id', 'item_list_name', 'item_list_index', 'promotion_id', 'promotion_name', 'creative_name', 'creative_slot'],
  event_dimensions: ['hostname'],
};

export const detectNestedColumns = (
  headerList: Array<{ name: string; dataType?: string }>,
  dataList: any[][]
): NestedColumnInfo[] => {
  const nestedColumns: NestedColumnInfo[] = [];

  headerList.forEach((header, colIndex) => {
    if (header.name === 'row number' || header.dataType === 'INQUERY_ROW_NUMBER') return;

    for (let i = 0; i < Math.min(3, dataList.length); i++) {
      const value = dataList[i]?.[colIndex];
      if (isBigQueryNestedJson(value)) {
        const parsed = parseBigQueryValue(value);
        const isArray = Array.isArray(parsed);
        let childColumns: string[] = [];

        if (KNOWN_NESTED_SCHEMAS[header.name]) {
          childColumns = KNOWN_NESTED_SCHEMAS[header.name];
        } else {
          if (isArray && parsed.length > 0 && Array.isArray(parsed[0])) {
            childColumns = parsed[0].map((_: any, idx: number) => `field_${idx}`);
          } else if (Array.isArray(parsed)) {
            childColumns = parsed.map((_: any, idx: number) => `field_${idx}`);
          }
        }

        nestedColumns.push({ parentColumn: header.name, childColumns, isArray });
        break;
      }
    }
  });

  return nestedColumns;
};

export const flattenDataWithNestedArrays = (
  headerList: Array<{ name: string; dataType?: string }>,
  dataList: any[][],
  nestedColumns: NestedColumnInfo[]
): {
  flattenedData: FlattenedRow[];
  expandedHeaders: Array<{ name: string; dataType?: string; isNested?: boolean; parentColumn?: string }>;
} => {
  const arrayColumns = nestedColumns.filter(
    (col) => col.isArray && (col.parentColumn === 'event_params' || col.parentColumn === 'user_properties')
  );
  const structColumns = nestedColumns.filter(
    (col) => !col.isArray || (col.isArray && col.parentColumn !== 'event_params' && col.parentColumn !== 'user_properties')
  );

  if (arrayColumns.length === 0 && structColumns.length === 0) {
    const flattenedData: FlattenedRow[] = dataList.map((row, idx) => {
      const rowObj: FlattenedRow = { _originalRowIndex: idx, _nestedIndex: 0, _rowSpan: 1, _isFirstOfGroup: true };
      headerList.forEach((header, colIdx) => { rowObj[header.name] = row[colIdx]; });
      return rowObj;
    });
    return { flattenedData, expandedHeaders: headerList };
  }

  const expandedHeaders: Array<{ name: string; dataType?: string; isNested?: boolean; parentColumn?: string }> = [];
  headerList.forEach((header) => {
    const arrayInfo = arrayColumns.find((nc) => nc.parentColumn === header.name);
    if (arrayInfo) {
      arrayInfo.childColumns.forEach((childCol) => {
        expandedHeaders.push({ name: `${header.name}.${childCol}`, dataType: 'STRING', isNested: true, parentColumn: header.name });
      });
      return;
    }
    const structInfo = structColumns.find((nc) => nc.parentColumn === header.name);
    if (structInfo) {
      structInfo.childColumns.forEach((childCol) => {
        expandedHeaders.push({ name: `${header.name}.${childCol}`, dataType: 'STRING', isNested: true, parentColumn: header.name });
      });
      return;
    }
    expandedHeaders.push(header);
  });

  const flattenedData: FlattenedRow[] = [];

  dataList.forEach((row, rowIdx) => {
    let maxArrayLength = 1;
    const parsedArrays = new Map<string, ParsedNestedValue[]>();

    arrayColumns.forEach((arrayCol) => {
      const colIdx = headerList.findIndex((h) => h.name === arrayCol.parentColumn);
      if (colIdx >= 0) {
        const parsed = parseEventParams(row[colIdx]);
        parsedArrays.set(arrayCol.parentColumn, parsed);
        maxArrayLength = Math.max(maxArrayLength, parsed.length || 1);
      }
    });

    const parsedStructs = new Map<string, Record<string, any>>();
    structColumns.forEach((structCol) => {
      const colIdx = headerList.findIndex((h) => h.name === structCol.parentColumn);
      if (colIdx >= 0) {
        const parsed = parseStructColumn(row[colIdx], structCol.childColumns);
        parsedStructs.set(structCol.parentColumn, parsed);
      }
    });

    for (let nestedIdx = 0; nestedIdx < maxArrayLength; nestedIdx++) {
      const flatRow: FlattenedRow = {
        _originalRowIndex: rowIdx,
        _nestedIndex: nestedIdx,
        _rowSpan: nestedIdx === 0 ? maxArrayLength : 0,
        _isFirstOfGroup: nestedIdx === 0,
      };

      headerList.forEach((header, colIdx) => {
        const arrayInfo = arrayColumns.find((nc) => nc.parentColumn === header.name);
        if (arrayInfo) {
          const parsedArray = parsedArrays.get(header.name) || [];
          const nestedItem = parsedArray[nestedIdx];
          if (nestedItem) {
            flatRow[`${header.name}.key`] = nestedItem.key;
            flatRow[`${header.name}.value.string_value`] = nestedItem.value.string_value;
            flatRow[`${header.name}.value.int_value`] = nestedItem.value.int_value;
            flatRow[`${header.name}.value.float_value`] = nestedItem.value.float_value;
            flatRow[`${header.name}.value.double_value`] = nestedItem.value.double_value;
          } else {
            flatRow[`${header.name}.key`] = null;
            flatRow[`${header.name}.value.string_value`] = null;
            flatRow[`${header.name}.value.int_value`] = null;
            flatRow[`${header.name}.value.float_value`] = null;
            flatRow[`${header.name}.value.double_value`] = null;
          }
          return;
        }

        const structInfo = structColumns.find((nc) => nc.parentColumn === header.name);
        if (structInfo) {
          const parsedStruct = parsedStructs.get(header.name) || {};
          structInfo.childColumns.forEach((childCol) => {
            flatRow[`${header.name}.${childCol}`] = parsedStruct[childCol] ?? null;
          });
          return;
        }

        flatRow[header.name] = row[colIdx];
      });

      flattenedData.push(flatRow);
    }
  });

  return { flattenedData, expandedHeaders };
};

export const getNestedDisplayValue = (value: any): string => {
  if (!isBigQueryNestedJson(value)) return String(value ?? '');
  try {
    const parsed = parseBigQueryValue(value);
    if (Array.isArray(parsed)) {
      const nonNullValues = parsed.filter((v) => v !== null && v !== '');
      return nonNullValues.slice(0, 3).join(', ') + (nonNullValues.length > 3 ? '...' : '');
    }
    return JSON.stringify(parsed);
  } catch {
    return String(value ?? '');
  }
};
