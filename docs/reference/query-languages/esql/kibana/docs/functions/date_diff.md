% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

### DATE DIFF
Subtracts the `startTimestamp` from the `endTimestamp` and returns the difference in multiples of `unit`.
If `startTimestamp` is later than the `endTimestamp`, negative values are returned.

```esql
ROW date1 = TO_DATETIME("2023-12-02T11:00:00.000Z"),
    date2 = TO_DATETIME("2023-12-02T11:00:00.001Z")
| EVAL dd_ms = DATE_DIFF("microseconds", date1, date2)
```
