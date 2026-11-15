"""Pure-stdlib JSON Schema subset validator for Logisim MCP tools & batch ops."""

from __future__ import annotations

from typing import Any, Dict, List


class SchemaValidationError(ValueError):
    """Raised when tool arguments fail schema validation."""


def _type_name(val: Any) -> str:
    if val is None:
        return "null"
    if isinstance(val, bool):
        return "boolean"
    if isinstance(val, int):
        return "integer"
    if isinstance(val, float):
        return "number"
    if isinstance(val, str):
        return "string"
    if isinstance(val, list):
        return "array"
    if isinstance(val, dict):
        return "object"
    return type(val).__name__


def _matches_type(val: Any, expected: str) -> bool:
    if expected == "boolean":
        return isinstance(val, bool)
    if expected == "integer":
        return isinstance(val, int) and not isinstance(val, bool)
    if expected == "number":
        return isinstance(val, (int, float)) and not isinstance(val, bool)
    if expected == "string":
        return isinstance(val, str)
    if expected == "array":
        return isinstance(val, list)
    if expected == "object":
        return isinstance(val, dict)
    if expected == "null":
        return val is None
    return True


def validate_schema(val: Any, schema: Dict[str, Any], path: str = "arguments") -> None:
    if not isinstance(schema, dict):
        return

    if "anyOf" in schema:
        errors: List[str] = []
        for sub in schema["anyOf"]:
            try:
                validate_schema(val, sub, path)
                return
            except SchemaValidationError as exc:
                errors.append(str(exc))
        raise SchemaValidationError(f"{path}: failed all anyOf variants ({'; '.join(errors[:2])})")

    if "oneOf" in schema:
        matched = 0
        errors = []
        for sub in schema["oneOf"]:
            try:
                validate_schema(val, sub, path)
                matched += 1
            except SchemaValidationError as exc:
                errors.append(str(exc))
        if matched != 1:
            raise SchemaValidationError(f"{path}: expected exactly 1 oneOf match, got {matched}")
        return

    expected_type = schema.get("type")
    if isinstance(expected_type, list):
        if not any(_matches_type(val, t) for t in expected_type):
            raise SchemaValidationError(
                f"{path}: expected one of types {expected_type}, got {_type_name(val)}"
            )
    elif isinstance(expected_type, str):
        if not _matches_type(val, expected_type):
            raise SchemaValidationError(
                f"{path}: expected type '{expected_type}', got {_type_name(val)}"
            )

    if "enum" in schema and val not in schema["enum"]:
        raise SchemaValidationError(
            f"{path}: value {val!r} is not in allowed enum {schema['enum']!r}"
        )

    if isinstance(val, (int, float)) and not isinstance(val, bool):
        if "minimum" in schema and val < schema["minimum"]:
            raise SchemaValidationError(f"{path}: {val} < minimum {schema['minimum']}")
        if "maximum" in schema and val > schema["maximum"]:
            raise SchemaValidationError(f"{path}: {val} > maximum {schema['maximum']}")

    if isinstance(val, str):
        if "minLength" in schema and len(val) < schema["minLength"]:
            raise SchemaValidationError(f"{path}: string length {len(val)} < minLength {schema['minLength']}")
        if "maxLength" in schema and len(val) > schema["maxLength"]:
            raise SchemaValidationError(f"{path}: string length {len(val)} > maxLength {schema['maxLength']}")

    if isinstance(val, list):
        if "minItems" in schema and len(val) < schema["minItems"]:
            raise SchemaValidationError(f"{path}: array has {len(val)} items < minItems {schema['minItems']}")
        if "maxItems" in schema and len(val) > schema["maxItems"]:
            raise SchemaValidationError(f"{path}: array has {len(val)} items > maxItems {schema['maxItems']}")
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for idx, item in enumerate(val):
                validate_schema(item, item_schema, f"{path}[{idx}]")

    if isinstance(val, dict):
        props = schema.get("properties", {})
        for req_key in schema.get("required", []):
            if req_key not in val or val[req_key] is None:
                raise SchemaValidationError(f"{path}: missing required field '{req_key}'")

        add_props = schema.get("additionalProperties", True)
        for k, v in val.items():
            if k in props:
                validate_schema(v, props[k], f"{path}.{k}")
            elif add_props is False:
                valid_keys = ", ".join(sorted(props.keys()))
                raise SchemaValidationError(
                    f"{path}: unknown property '{k}'. Allowed properties: [{valid_keys}]"
                )
            elif isinstance(add_props, dict):
                validate_schema(v, add_props, f"{path}.{k}")


def validate_tool_call(
    tool_name: str,
    arguments: Any,
    tool_schemas: Dict[str, Dict[str, Any]],
    batch_op_schemas: Dict[str, Dict[str, Any]],
) -> None:
    if tool_name not in tool_schemas:
        raise SchemaValidationError(f"Unknown tool: '{tool_name}'")
    args = {} if arguments is None else arguments
    if not isinstance(args, dict):
        raise SchemaValidationError(f"Tool arguments for '{tool_name}' must be a JSON object")

    validate_schema(args, tool_schemas[tool_name], path=tool_name)

    if tool_name == "logisim_batch":
        ops = args.get("operations", [])
        seen_refs: set[str] = set()
        for idx, raw_op in enumerate(ops):
            op_path = f"logisim_batch.operations[{idx}]"
            if not isinstance(raw_op, dict):
                raise SchemaValidationError(f"{op_path}: must be an object")
            op_name = raw_op.get("op")
            if not isinstance(op_name, str) or op_name not in batch_op_schemas:
                allowed = ", ".join(sorted(batch_op_schemas.keys()))
                raise SchemaValidationError(
                    f"{op_path}.op: unsupported op {op_name!r}. Valid batch ops: [{allowed}]"
                )
            op_params = {k: v for k, v in raw_op.items() if k != "op"}
            validate_schema(op_params, batch_op_schemas[op_name], path=f"{op_path}({op_name})")
            ref = op_params.get("ref")
            if isinstance(ref, str) and ref.strip():
                r = ref.strip()
                if r in seen_refs:
                    raise SchemaValidationError(f"{op_path}.ref: duplicate batch reference '{r}'")
                seen_refs.add(r)
