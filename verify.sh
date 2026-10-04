#!/usr/bin/env bash
# 端到端验证脚本：覆盖登记→上报→审核→派单→称重→处置→计费→投诉→追责全链路
# 前置：应用已启动（默认 http://localhost:8080），数据库为全新状态（演示数据已初始化）
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
PASS=0; FAIL=0

check() { # $1=描述 $2=实际内容 $3=期望包含的子串
  if [[ "$2" == *"$3"* ]]; then
    PASS=$((PASS+1)); echo "  ✔ $1"
  else
    FAIL=$((FAIL+1)); echo "  ✘ $1"; echo "    期望包含: $3"; echo "    实际: $(echo "$2" | head -c 400)"
  fi
}

login() { curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" | jq -r '.data.token'; }

J() { jq -r "$1" 2>/dev/null; }

TODAY=$(date +%F)
YESTERDAY=$(date -d "yesterday" +%F)
TOMORROW=$(date -d "tomorrow" +%F)
NEXT_MONDAY=$(date -d "next monday" +%F)
NOW=$(date +%FT%T)
PLUS2H=$(date -d "+2 hours" +%FT%T)
PERIOD=$(date +%Y-%m)

echo "== 0. 认证与鉴权 =="
UNAUTH=$(curl -s "$BASE/api/registrations")
check "未登录访问返回 401" "$UNAUTH" "未登录"
OWNER_T=$(login owner1 123456);  OWNER2_T=$(login owner2 123456)
WORKER_T=$(login worker1 123456); PROP_T=$(login property1 123456)
COLL_T=$(login collector1 123456); FIN_T=$(login finance1 123456)
check "六个角色登录获取令牌" "$([ -n "$OWNER_T" ] && [ -n "$FIN_T" ] && echo ok)" "ok"
ME=$(curl -s "$BASE/api/auth/me" -H "Authorization: Bearer $OWNER_T")
check "当前用户为张三(OWNER)" "$ME" '"name":"张三"'

AUTH_PROP="Authorization: Bearer $PROP_T"; AUTH_COLL="Authorization: Bearer $COLL_T"
AUTH_FIN="Authorization: Bearer $FIN_T"; AUTH_OWNER="Authorization: Bearer $OWNER_T"
AUTH_WORKER="Authorization: Bearer $WORKER_T"; CT="Content-Type: application/json"

echo "== 1. 主流程：上报→审核→派单→称重→处置→计费 =="
R1=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":1,\"reportDate\":\"$TODAY\",\"bagCount\":10,\"extraBagCount\":2,\"stackingLocation\":\"1号楼垃圾点旁\",\"wastePointId\":1,\"containsWood\":true,\"containsGlass\":true,\"containsTile\":true,\"containsMixedHousehold\":false}")
REPORT1=$(echo "$R1" | J '.data.id')
check "施工队每日上报(10袋含2加袋)" "$R1" '"status":"PENDING"'

A1=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT1,\"approve\":true,\"apptDate\":\"$TODAY\",\"timeSlot\":\"AM\",\"comment\":\"同意清运\"}")
APPT1=$(echo "$A1" | J '.data.id')
check "物业审核通过生成预约" "$A1" '"status":"APPROVED"'

D1=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT1,\"vehicleId\":1,\"plannedArrival\":\"$PLUS2H\",\"routeInfo\":\"东门进→1号楼→西门出\"}")
DISP1=$(echo "$D1" | J '.data.id')
check "清运公司派单(含门禁放行单号)" "$D1" '"gatePassNo":"GP'

ARR=$(curl -s -X POST "$BASE/api/dispatches/$DISP1/arrive" -H "$AUTH_COLL" -H "$CT" -d "{\"actualArrival\":\"$NOW\"}")
check "司机到场登记(准时)" "$ARR" '"lateFlag":false'

W1=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$DISP1,\"emptyWeightKg\":5000,\"loadedWeightKg\":5250,\"entryTime\":\"$NOW\",\"exitTime\":\"$(date +%FT%T)\",\"photos\":\"http://img/1.jpg\",\"manifestNo\":\"MF-TEST-001\"}")
WEIGH1=$(echo "$W1" | J '.data.id')
check "进出场称重(净重250kg无异常)" "$W1" '"anomalyFlag":false'

RC=$(curl -s -X POST "$BASE/api/disposal-receipts" -H "$AUTH_COLL" -H "$CT" -d "{\"weighRecordId\":$WEIGH1,\"disposalSite\":\"城东建筑垃圾处置场\",\"receivedWeightKg\":250}")
check "处置场回执" "$RC" '"receiptNo":"RC'

sleep 1  # 等待计费事件处理
BILLS=$(curl -s "$BASE/api/bills?type=OWNER" -H "$AUTH_FIN")
check "业主账单=88元(8袋×8+2加袋×8×1.5)" "$BILLS" '"amount":88.00'
check "业主账单押金抵扣已结清" "$BILLS" '"status":"PAID"'
OWNER_BILL=$(echo "$BILLS" | J '[.data[] | select(.appointmentId=='"$APPT1"')][0].id')
CBILLS=$(curl -s "$BASE/api/bills?type=COLLECTOR" -H "$AUTH_FIN")
check "清运公司结算单=30元(0.25吨×120)" "$CBILLS" '"amount":30.00'
COLL_BILL=$(echo "$CBILLS" | J '[.data[] | select(.appointmentId=='"$APPT1"')][0].id')
PAY=$(curl -s -X POST "$BASE/api/bills/$COLL_BILL/pay" -H "$AUTH_FIN")
check "财务结清清运公司账单" "$PAY" '"status":"PAID"'

TRACE=$(curl -s "$BASE/api/trace/appointments/$APPT1" -H "$AUTH_PROP")
for kw in SUBMIT_REPORT REVIEW_APPROVE DISPATCH_CREATE WEIGH_IN_OUT DISPOSAL_RECEIPT BILL_GENERATE '"status":"SETTLED"' 张三 李工 王经理 刘师傅; do
  check "全链路追溯包含 $kw" "$TRACE" "$kw"
done
TCODE=$(echo "$TRACE" | J '.data.bagTrace.traceCode')
check "按追溯码查询" "$(curl -s "$BASE/api/trace/code/$TCODE" -H "$AUTH_PROP")" '"apptNo"'

echo "== 2. 容量规则：接近上限拒绝→临时围挡→通过 =="
REG2=$(curl -s -X POST "$BASE/api/registrations" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"building\":\"2栋\",\"roomNumber\":\"502\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+20 days' +%F)\",\"wasteTypes\":\"瓷砖,混凝土\",\"frequency\":\"DAILY\",\"depositAmount\":500}" | J '.data.id')
check "业主2登记(押金500)" "$([ -n "$REG2" ] && [ "$REG2" != "null" ] && echo ok)" "ok"

R2=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG2,\"reportDate\":\"$TODAY\",\"bagCount\":10,\"extraBagCount\":0,\"stackingLocation\":\"2号楼垃圾点\",\"wastePointId\":2,\"containsWood\":false,\"containsGlass\":false,\"containsTile\":true,\"containsMixedHousehold\":false}")
REPORT2=$(echo "$R2" | J '.data.id')

A2=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT2,\"approve\":true,\"apptDate\":\"$NEXT_MONDAY\",\"timeSlot\":\"AM\",\"comment\":\"\"}")
check "垃圾点接近上限→不简单通过" "$A2" '"status":"REJECTED_CAPACITY"'
check "提示错峰堆放" "$A2" "错峰"
check "提示临时围挡" "$A2" "围挡"

FENCE=$(curl -s -X POST "$BASE/api/waste-points/2/fence" -H "$AUTH_PROP" -H "$CT" -d '{"enable":true,"extraCapacity":20}')
check "物业安排临时围挡扩容+20" "$FENCE" '"tempFence":true'

A2B=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT2,\"approve\":true,\"apptDate\":\"$NEXT_MONDAY\",\"timeSlot\":\"AM\",\"comment\":\"已围挡\"}")
APPT2=$(echo "$A2B" | J '.data.id')
check "围挡扩容后审核通过" "$A2B" '"status":"APPROVED"'

D2X=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT2,\"vehicleId\":3,\"plannedArrival\":\"${NEXT_MONDAY}T10:00:00\"}")
check "尾号6车辆周一限行被拒" "$D2X" "限行"

D2=$(curl -s -X POST "$BASE/api/dispatches" -H "$AUTH_COLL" -H "$CT" -d "{\"appointmentId\":$APPT2,\"vehicleId\":1,\"plannedArrival\":\"${NEXT_MONDAY}T10:00:00\"}")
DISP2=$(echo "$D2" | J '.data.id')
check "更换车辆派单成功" "$D2" '"status":"DISPATCHED"'

W2=$(curl -s -X POST "$BASE/api/weigh-records" -H "$AUTH_COLL" -H "$CT" -d "{\"dispatchId\":$DISP2,\"emptyWeightKg\":5000,\"loadedWeightKg\":5400,\"entryTime\":\"$NOW\",\"exitTime\":\"$(date +%FT%T)\",\"photos\":\"http://img/2.jpg\"}")
check "称重偏差60%→标记异常" "$W2" '"anomalyFlag":true'
sleep 1
CPLS=$(curl -s "$BASE/api/complaints" -H "$AUTH_PROP")
check "称重异常自动生成投诉" "$CPLS" '"type":"WEIGH_ANOMALY"'

echo "== 3. 违规：消防通道堆放+混入生活垃圾→押金扣款→施工队账单 =="
R3=$(curl -s -X POST "$BASE/api/reports" -H "$AUTH_WORKER" -H "$CT" -d "{\"registrationId\":$REG2,\"reportDate\":\"$TOMORROW\",\"bagCount\":5,\"extraBagCount\":0,\"stackingLocation\":\"2号楼消防通道口\",\"wastePointId\":2,\"containsWood\":false,\"containsGlass\":false,\"containsTile\":false,\"containsMixedHousehold\":true}")
REPORT3=$(echo "$R3" | J '.data.id')
sleep 1
VIO=$(curl -s "$BASE/api/violations" -H "$AUTH_PROP")
check "消防通道堆放违规" "$VIO" '"type":"FIRE_LANE_STACKING"'
check "混入生活垃圾违规" "$VIO" '"type":"MIXED_HOUSEHOLD_WASTE"'
DEP=$(curl -s "$BASE/api/registrations/$REG2/deposit-log" -H "$AUTH_PROP")
check "押金扣款200(消防通道)" "$DEP" '"amount":200.00'
check "押金扣款100(混入生活垃圾)" "$DEP" '"amount":100.00'
REG2INFO=$(curl -s "$BASE/api/registrations/$REG2" -H "$AUTH_PROP")
check "押金余额500-300-80(流程B清运费)=120" "$REG2INFO" '"depositBalance":120.00'
TEAM_BILLS=$(curl -s "$BASE/api/bills?type=CONSTRUCTION_TEAM" -H "$AUTH_FIN")
check "施工队罚款账单生成" "$TEAM_BILLS" '"billType":"CONSTRUCTION_TEAM"'
TEAM_BILL=$(echo "$TEAM_BILLS" | J '[.data[] | select(.amount==200)][0].id')
PAYT=$(curl -s -X POST "$BASE/api/bills/$TEAM_BILL/pay" -H "$AUTH_FIN")
check "施工队缴清罚款" "$PAYT" '"status":"PAID"'
REG2INFO2=$(curl -s "$BASE/api/registrations/$REG2" -H "$AUTH_PROP")
check "罚款返还业主押金→320" "$REG2INFO2" '"depositBalance":320.00'

A3=$(curl -s -X POST "$BASE/api/appointments/review" -H "$AUTH_PROP" -H "$CT" -d "{\"reportId\":$REPORT3,\"approve\":true,\"apptDate\":\"$YESTERDAY\",\"timeSlot\":\"PM\",\"comment\":\"限期清理\"}")
check "违规上报仍可审核(昨日预约→超时未清)" "$A3" '"status":"APPROVED"'

echo "== 4. 费用争议：投诉→账单冻结→财务调整→押金回冲 =="
CD=$(curl -s -X POST "$BASE/api/complaints" -H "$AUTH_OWNER" -H "$CT" -d "{\"type\":\"FEE_DISPUTE\",\"billId\":$OWNER_BILL,\"description\":\"加袋费用有异议，要求复核\"}")
CPL1=$(echo "$CD" | J '.data.id')
check "业主发起费用争议投诉" "$CD" '"status":"OPEN"'
BILL_NOW=$(curl -s "$BASE/api/bills/$OWNER_BILL" -H "$AUTH_FIN")
check "账单自动转为争议状态" "$BILL_NOW" '"status":"DISPUTED"'
ADJ=$(curl -s -X POST "$BASE/api/bills/$OWNER_BILL/adjust" -H "$AUTH_FIN" -H "$CT" -d '{"newAmount":60,"reason":"复核后加袋按普通袋计费"}')
check "财务调整账单88→60" "$ADJ" '"amount":60.00'
REG1INFO=$(curl -s "$BASE/api/registrations/1" -H "$AUTH_PROP")
check "差额28元回冲押金(1912→1940)" "$REG1INFO" '"depositBalance":1940.00'
HDL=$(curl -s -X PUT "$BASE/api/complaints/$CPL1/handle" -H "$AUTH_PROP" -H "$CT" -d '{"action":"RESOLVED","resolution":"已复核调整账单并退还差额"}')
check "物业办结投诉" "$HDL" '"status":"RESOLVED"'

ODOR=$(curl -s -X POST "$BASE/api/complaints" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"type\":\"ODOR\",\"targetAppointmentId\":$APPT1,\"description\":\"垃圾点异味扰民\"}")
check "邻居投诉异味" "$ODOR" '"complaintNo":"CPL'

echo "== 5. 三端看板 =="
DP=$(curl -s "$BASE/api/dashboard/property" -H "$AUTH_PROP")
check "物业看板-楼栋统计" "$DP" '"building":"1栋"'
check "物业看板-超时未清" "$DP" '"overdueAppointments":[{'
check "物业看板-清运公司履约" "$DP" '"company":"绿洁清运"'
DC=$(curl -s "$BASE/api/dashboard/collector" -H "$AUTH_COLL")
check "清运看板-派单与车辆" "$DC" '"vehicles"'
DF=$(curl -s "$BASE/api/dashboard/finance?period=$PERIOD" -H "$AUTH_FIN")
check "财务看板-四类账单汇总" "$DF" '"PROPERTY_ADVANCE"'

echo "== 6. 消防通道违规堆放处置：建单→限期移走仅警告 / 超期通知→二次巡查扣款 / 邻居投诉扣款 =="
REG3=$(curl -s -X POST "$BASE/api/registrations" -H "$AUTH_OWNER" -H "$CT" -d "{\"building\":\"3栋\",\"roomNumber\":\"902\",\"constructionTeam\":\"宏远装修队\",\"expectedStartDate\":\"$TODAY\",\"expectedEndDate\":\"$(date -d '+20 days' +%F)\",\"wasteTypes\":\"木板\",\"frequency\":\"DAILY\",\"depositAmount\":1000}" | J '.data.id')
check "业主登记3栋902(押金1000)" "$([ -n "$REG3" ] && [ "$REG3" != "null" ] && echo ok)" "ok"

# 6.1 夜间巡查建单
FA=$(curl -s -X POST "$BASE/api/fire-lane/rectifications" -H "$AUTH_PROP" -H "$CT" -d "{\"registrationId\":$REG3,\"patrolTime\":\"$NOW\",\"scenePhotos\":\"http://img/fl-a1.jpg,http://img/fl-a2.jpg\",\"stackingLocation\":\"3栋一层东侧消防通道\",\"materialDesc\":\"木板约20张\"}")
FLA=$(echo "$FA" | J '.data.id')
check "夜巡建整改任务(关联房号/施工队)" "$FA" '"taskNo":"FLR'
check "任务自动带出施工队" "$FA" '"constructionTeam":"宏远装修队"'
check "任务自动带出施工队联系方式" "$FA" '"teamPhone":"13800002001"'
check "任务记录现场照片与巡查时间" "$FA" '"scenePhotos":"http://img/fl-a1.jpg'
check "任务状态待整改" "$FA" '"status":"PENDING"'
check "任务含整改截止时间" "$FA" '"deadline":"'

# 6.2 限期内移走 → 仅警告，不扣款
CA=$(curl -s -X POST "$BASE/api/fire-lane/rectifications/$FLA/clear" -H "$AUTH_WORKER" -H "$CT" -d "{\"clearedAt\":\"$PLUS2H\",\"photos\":\"http://img/fl-clear.jpg\"}")
check "施工队限期内移走" "$CA" '"status":"RECTIFIED"'
check "限期移走二次巡查结果=已清" "$CA" '"patrolResult":"CLEARED"'
DEP3_EMPTY=$(curl -s "$BASE/api/registrations/$REG3/deposit-log" -H "$AUTH_PROP")
check "限期移走押金不扣款(无流水)" "$DEP3_EMPTY" '"data":[]'
CRED_A=$(curl -s -G "$BASE/api/fire-lane/credit" --data-urlencode "constructionTeam=宏远装修队" -H "$AUTH_PROP")
check "信用记录警告(95分)" "$CRED_A" '"currentScore":95'
check "信用警告1次" "$CRED_A" '"warningCount":1'

# 6.3 超期未移走 → 自动通知物业经理与业主；二次巡查仍未移走 → 计时扣款
PATROL_B=$(date -d "-42 hours" +%FT%T)
FB=$(curl -s -X POST "$BASE/api/fire-lane/rectifications" -H "$AUTH_PROP" -H "$CT" -d "{\"registrationId\":$REG3,\"patrolTime\":\"$PATROL_B\",\"scenePhotos\":\"http://img/fl-b.jpg\",\"stackingLocation\":\"3栋一层西侧消防通道\",\"materialDesc\":\"木板、木方一堆\"}")
FLB=$(echo "$FB" | J '.data.id')
SCAN=$(curl -s -X POST "$BASE/api/fire-lane/scan-overdue" -H "$AUTH_PROP")
sleep 1
FLB_NOW=$(curl -s "$BASE/api/fire-lane/rectifications/$FLB" -H "$AUTH_PROP")
check "超期任务自动转已通知" "$FLB_NOW" '"status":"OVERDUE_NOTIFIED"'
check "超期任务记录占用时长(扣款依据)" "$FLB_NOW" '"impactLevel":"SERIOUS"'
PROP_NTF=$(curl -s "$BASE/api/notifications" -H "$AUTH_PROP")
check "自动通知物业经理" "$PROP_NTF" '"bizType":"FIRE_LANE_OVERDUE"'
OWNER_NTF=$(curl -s "$BASE/api/notifications" -H "$AUTH_OWNER")
check "自动通知业主本人" "$OWNER_NTF" '"bizType":"FIRE_LANE_OVERDUE"'
check "通知内容含影响范围/扣款规则" "$OWNER_NTF" "消防通道"

RP=$(curl -s -X POST "$BASE/api/fire-lane/rectifications/$FLB/re-patrol" -H "$AUTH_PROP" -H "$CT" -d "{\"result\":\"NOT_CLEARED\",\"at\":\"$(date +%FT%T)\",\"photos\":\"http://img/fl-b2.jpg\",\"impactScope\":\"整幅通道被木板阻断，影响3栋两个单元疏散\"}")
check "二次巡查未移走→按规则扣款" "$RP" '"status":"DEDUCTED"'
check "扣款=基础200+超期2个时间块200+二次巡查200=600" "$RP" '"deductionAmount":600.00'
check "扣款生成施工队罚款账单" "$RP" '"teamBillNo":"BILL'
check "二次巡查结果写入任务" "$RP" '"patrolResult":"NOT_CLEARED"'
DEP3_B=$(curl -s "$BASE/api/registrations/$REG3/deposit-log" -H "$AUTH_PROP")
check "押金按扣款依据扣除600" "$DEP3_B" '"amount":600.00'
REG3_B=$(curl -s "$BASE/api/registrations/$REG3" -H "$AUTH_PROP")
check "押金余额1000-600=400" "$REG3_B" '"depositBalance":400.00'
CRED_B=$(curl -s -G "$BASE/api/fire-lane/credit" --data-urlencode "constructionTeam=宏远装修队" -H "$AUTH_PROP")
check "信用扣款记录入信用档案(75分)" "$CRED_B" '"currentScore":75'

# 6.4 邻居投诉占用消防通道 → 在办任务立即按规则扣款
PATROL_C=$(date -d "-2 hours" +%FT%T)
FC=$(curl -s -X POST "$BASE/api/fire-lane/rectifications" -H "$AUTH_PROP" -H "$CT" -d "{\"registrationId\":$REG3,\"patrolTime\":\"$PATROL_C\",\"scenePhotos\":\"http://img/fl-c.jpg\",\"stackingLocation\":\"3栋一层消防通道口\",\"materialDesc\":\"木板占道\"}")
FLC=$(echo "$FC" | J '.data.id')
CPC=$(curl -s -X POST "$BASE/api/complaints" -H "Authorization: Bearer $OWNER2_T" -H "$CT" -d "{\"type\":\"FIRE_LANE\",\"targetRegistrationId\":$REG3,\"description\":\"3栋消防通道被木板堵死，严重影响疏散，要求立即处理\"}")
check "邻居发起消防通道投诉" "$CPC" '"type":"FIRE_LANE"'
sleep 1
FLC_NOW=$(curl -s "$BASE/api/fire-lane/rectifications/$FLC" -H "$AUTH_PROP")
check "投诉触发整改任务扣款" "$FLC_NOW" '"status":"DEDUCTED"'
check "记录邻居投诉标志" "$FLC_NOW" '"neighborComplaint":true'
check "扣款=基础200+投诉200(限期内无超时)=400" "$FLC_NOW" '"deductionAmount":400.00'
REG3_C=$(curl -s "$BASE/api/registrations/$REG3" -H "$AUTH_PROP")
check "押金余额400-400=0" "$REG3_C" '"depositBalance":0.00'
TEAM_BILLS2=$(curl -s "$BASE/api/bills?type=CONSTRUCTION_TEAM" -H "$AUTH_FIN")
check "施工队消防罚款账单600" "$TEAM_BILLS2" '"amount":600.00'
check "施工队消防罚款账单400" "$TEAM_BILLS2" '"amount":400.00'
CRED_C=$(curl -s -G "$BASE/api/fire-lane/credit" --data-urlencode "constructionTeam=宏远装修队" -H "$AUTH_PROP")
check "信用分累计55(100-5-20-20)" "$CRED_C" '"currentScore":55'
check "信用扣款2次" "$CRED_C" '"deductionCount":2'
NID=$(echo "$PROP_NTF" | J '.data[0].id')
check "通知标记已读" "$(curl -s -X POST "$BASE/api/notifications/$NID/read" -H "$AUTH_PROP")" '"readFlag":true'

DP2=$(curl -s "$BASE/api/dashboard/property" -H "$AUTH_PROP")
check "物业看板-消防整改任务数3" "$DP2" '"fireLaneRectifications"'
check "物业看板-整改扣款合计1000" "$DP2" '"totalDeducted":1000.00'

echo ""
echo "==================================="
echo "验证结果: 通过 $PASS 项, 失败 $FAIL 项"
[ "$FAIL" -eq 0 ] && echo "ALL CHECKS PASSED" || exit 1
