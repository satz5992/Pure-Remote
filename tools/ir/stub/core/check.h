#pragma once
#include <assert.h>
#include <stdlib.h>
#define furi_assert(x) assert(x)
#define furi_check(x) assert(x)
#define furi_crash(...) abort()
