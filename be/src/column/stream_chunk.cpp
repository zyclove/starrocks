// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "column/stream_chunk.h"

#include "gen_cpp/MVMaintenance_types.h"

namespace starrocks {

EpochInfo EpochInfo::from_start_epoch_task(const TMVStartEpochTask& start_epoch) {
    EpochInfo res;
    res.epoch_id = start_epoch.epoch.epoch_id;
    res.txn_id = start_epoch.epoch.txn_id;
    res.max_exec_millis = start_epoch.max_exec_millis;
    res.max_scan_rows = start_epoch.max_scan_rows;
    return res;
}

} // namespace starrocks
