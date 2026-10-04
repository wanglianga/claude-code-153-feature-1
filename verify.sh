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

echo "== 6. 消防通道夜间巡查整改：限内移走仅警告 =="
AGO2H=$(date -d "2 hours ago" +%FT%T)
# 场景A：业主张四(REG2) 的施工队堆放木板，未阻断通道；限内移走 → 仅警告不扣款
CA=$(curl -s -X POST "$BASE/api/rectifications" -H "$AUTH_PROP" -H "$CT" -d "{\"registrationId\":$REG2,\"scenePhotos\":\"http://img/patrol-a1.jpg\",\"fireLaneLocation\":\"2号楼一层东侧消防通道\",\"material\":\"木板\",\"impactScope\":\"半幅堆放，人员可绕行\",\"fireLaneBlocked\":false,\"deadlineMinutes\":120}")
TASKA=$(echo "$CA" | J '.data.id')
check "巡查生成整改任务(OPEN+限期)" "$CA" '"status":"OPEN"'
check "任务带房号装修登记信息" "$CA" '"roomNumber":"502"'
check "任务带施工队联系方式" "$CA" '"teamContact":"李工/13800002001"'
check "任务带现场照片与巡查时间" "$CA" '"scenePhotos":"http://img/patrol-a1.jpg"'

CLA=$(curl -s -X POST "$BASE/api/rectifications/$TASKA/clear" -H "$AUTH_WORKER" -H "$CT" -d '{"clearPhotos":"http://img/clear-a1.jpg"}')
check "限内移走→CLEARED仅警告" "$CLA" '"status":"CLEARED"'
check "警告不扣款(扣款0元)" "$CLA" '"deductionAmount":0.00'
check "结案说明为警告" "$CLA" "仅记录警告"
check "押金保持320不变" "$(curl -s "$BASE/api/registrations/$REG2" -H "$AUTH_PROP")" '"depositBalance":320.00'
VIOA=$(curl -s "$BASE/api/violations" -H "$AUTH_PROP")
check "关联违规单随警告豁免" "$VIOA" '"rectificationTaskId":'"$TASKA"
CRW=$(curl -s "$BASE/api/rectifications/team-credits" -H "$AUTH_WORKER")
check "施工队信用写入警告记录" "$CRW" '"type":"WARNING"'
check "警告信用扣0分/0元" "$CRW" '"scoreDeducted":0'

echo "== 7. 消防通道整改：阻断通道+超时未移走→自动通知→二次巡查扣款 =="
# 场景B：张三(REG1)施工队木板阻断消防通道，巡查时间=2小时前，时限0分钟，立即超时
CB=$(curl -s -X POST "$BASE/api/rectifications" -H "$AUTH_PROP" -H "$CT" -d "{\"registrationId\":1,\"scenePhotos\":\"http://img/patrol-b1.jpg,http://img/patrol-b2.jpg\",\"fireLaneLocation\":\"1号楼一层西侧消防通道\",\"material\":\"木板\",\"impactScope\":\"阻断1号楼西侧疏散出口，3米通道被占2米\",\"fireLaneBlocked\":true,\"patrolTime\":\"$AGO2H\",\"deadlineMinutes\":0}")
TASKB=$(echo "$CB" | J '.data.id')
check "阻断通道整改任务建立" "$CB" '"fireLaneBlocked":true'
SCAN=$(curl -s -X POST "$BASE/api/rectifications/scan-overdue" -H "$AUTH_PROP")
check "超时扫描通知≥1个任务" "$SCAN" '"overdueNotified":'
TB=$(curl -s "$BASE/api/rectifications/$TASKB" -H "$AUTH_PROP")
check "任务置为OVERDUE" "$TB" '"status":"OVERDUE"'
check "记录消防通道占用时长(分钟)" "$TB" '"occupationMinutes":'
check "记录超时通知时间" "$TB" '"overdueNotifiedAt":'
N_MGR=$(curl -s "$BASE/api/notifications?receiverRole=PROPERTY_MANAGER" -H "$AUTH_PROP")
check "自动通知物业经理(超时)" "$N_MGR" "消防通道堆放整改任务超时"
check "通知含影响范围" "$N_MGR" "影响范围"
N_TASK=$(curl -s "$BASE/api/notifications/task/$TASKB" -H "$AUTH_PROP")
check "通知含二次巡查结论字段" "$N_TASK" "二次巡查"
N_OWNER=$(curl -s "$BASE/api/notifications/mine" -H "$AUTH_OWNER")
check "自动通知业主本人(短信)" "$N_OWNER" '"receiverRole":"OWNER"'
check "业主通知含施工队与房号" "$N_OWNER" "宏远装修队"
SCAN2=$(curl -s -X POST "$BASE/api/rectifications/scan-overdue" -H "$AUTH_PROP")
check "重复扫描幂等(本次0个)" "$SCAN2" '"overdueNotified":0'

# 二次巡查：仍在占用 → 阻断500 + 超时2小时×50 = 600元
PB=$(curl -s -X POST "$BASE/api/rectifications/$TASKB/second-patrol" -H "$AUTH_PROP" -H "$CT" -d '{"result":"STILL_THERE","photos":"http://img/patrol-b3.jpg","note":"二次巡查木板仍堆放在消防通道，通道仍被阻断"}')
check "二巡仍占用→PENALIZED" "$PB" '"status":"PENALIZED"'
check "扣款600=阻断500+超时2小时×50" "$PB" '"deductionAmount":600.00'
check "二巡结果作为扣款依据" "$PB" '"secondPatrolResult":"STILL_THERE"'
check "占用时长约120分钟" "$PB" '"occupationMinutes":120'
check "影响范围写入结案依据" "$PB" "阻断1号楼西侧疏散出口"
REG1B=$(curl -s "$BASE/api/registrations/1" -H "$AUTH_PROP")
check "押金1940-600=1340" "$REG1B" '"depositBalance":1340.00'
DEP1=$(curl -s "$BASE/api/registrations/1/deposit-log" -H "$AUTH_PROP")
check "押金流水记录600扣款" "$DEP1" '"amount":600.00'
TEAM650=$(curl -s "$BASE/api/bills?type=CONSTRUCTION_TEAM" -H "$AUTH_FIN")
check "施工队600元罚款账单" "$TEAM650" '"amount":600.00'
VIOB=$(curl -s "$BASE/api/violations?registrationId=1" -H "$AUTH_PROP")
check "违规单扣款600已结案" "$VIOB" '"deductionAmount":600.00'
CRB=$(curl -s "$BASE/api/rectifications/team-credits?team=%E5%AE%8F%E8%BF%9C%E8%A3%85%E4%BF%AE%E9%98%9F" -H "$AUTH_PROP")
check "信用写入处罚记录" "$CRB" '"type":"PENALTY"'
check "处罚信用扣5分" "$CRB" '"scoreDeducted":5'

echo "== 8. 消防通道整改：邻居投诉联动扣款（含押金不足垫付） =="
CC=$(curl -s -X POST "$BASE/api/rectifications" -H "$AUTH_PROP" -H "$CT" -d "{\"registrationId\":$REG2,\"scenePhotos\":\"http://img/patrol-c1.jpg\",\"fireLaneLocation\":\"2号楼一层东侧消防通道\",\"material\":\"木板\",\"impactScope\":\"占用东侧通道约1米\",\"fireLaneBlocked\":false,\"deadlineMinutes\":120}")
TASKC=$(echo "$CC" | J '.data.id')
check "邻居投诉前任务待整改" "$CC" '"status":"OPEN"'
CPLNB=$(curl -s -X POST "$BASE/api/complaints" -H "$AUTH_OWNER" -H "$CT" -d "{\"type\":\"FIRE_LANE\",\"targetRegistrationId\":$REG2,\"description\":\"2栋502施工队木板占了消防通道，老人轮椅过不去\"}")
check "邻居投诉占用消防通道" "$CPLNB" '"type":"FIRE_LANE"'
TC1=$(curl -s "$BASE/api/rectifications/$TASKC" -H "$AUTH_PROP")
check "投诉联动任务直接扣款结案" "$TC1" '"status":"PENALIZED"'
check "邻居投诉扣款200" "$TC1" '"deductionAmount":200.00'
check "押金320-200=120" "$(curl -s "$BASE/api/registrations/$REG2" -H "$AUTH_PROP")" '"depositBalance":120.00'
# 第二次投诉 → 追加200，押金不足(120)→物业垫付80
curl -s -X POST "$BASE/api/complaints" -H "$AUTH_OWNER" -H "$CT" -d "{\"type\":\"FIRE_LANE\",\"targetRegistrationId\":$REG2,\"description\":\"再次投诉：消防通道仍有木板\"}" >/dev/null
TC2=$(curl -s "$BASE/api/rectifications/$TASKC" -H "$AUTH_PROP")
check "二次投诉追加扣款累计400" "$TC2" '"deductionAmount":400.00'
check "押金不足扣至0" "$(curl -s "$BASE/api/registrations/$REG2" -H "$AUTH_PROP")" '"depositBalance":0.00'
ADVB=$(curl -s "$BASE/api/bills?type=PROPERTY_ADVANCE" -H "$AUTH_FIN")
check "缺口80元生成物业垫付单" "$ADVB" '"amount":80.00'

echo "== 9. 整改看板与数据权限 =="
DP2=$(curl -s "$BASE/api/dashboard/property" -H "$AUTH_PROP")
check "物业看板-整改任务统计" "$DP2" '"rectificationTasks"'
check "物业看板-在办整改任务" "$DP2" '"activeTasks"'
check "物业看板-施工队信用汇总" "$DP2" '"teamCredit"'
check "物业看板-处罚案件计数" "$DP2" '"penalized":2'
LIST_OWNER2=$(curl -s "$BASE/api/rectifications" -H "Authorization: Bearer $OWNER2_T")
check "业主只见本户整改任务(不见REG1任务)" "$(echo "$LIST_OWNER2" | J "[.data[] | select(.id==$TASKB)] | length")" "0"
LIST_WORKER=$(curl -s "$BASE/api/rectifications" -H "$AUTH_WORKER")
check "施工队可见本队整改任务" "$LIST_WORKER" "$TASKA"

echo ""
echo "==================================="
echo "验证结果: 通过 $PASS 项, 失败 $FAIL 项"
[ "$FAIL" -eq 0 ] && echo "ALL CHECKS PASSED" || exit 1
