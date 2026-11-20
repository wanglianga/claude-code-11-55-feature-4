#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验收回放脚本：碳排放披露数据血缘与版本影响管理平台
回放场景：
  S0 基线：种子数据（2025-05 已发布报告）核对
  S1 来源补录 → 影响评估 → 重述建议 → 接受 → 重新发布（历史保留）
  S2 因子修订：运行中替换因子，当前运行仍绑定启动清单；新运行用新版
  S3 并发采购更正：已核算采购量不可覆盖；同批次并发修订冲突
  S4 勾稽不平：报告保持待核，审批/发布被拒；修复后放行
  S5 已发布报告发现来源缺失：生成重述建议与差异说明，不覆盖已发布报告
  S6 共用资源守恒与证据等级：等级法（子表/系数）与平衡法（可行区间）同为正式口径
  S7 子表矛盾：两个产品线申领合计超过总表 → 阻断，不产生超总量结果
  S8 缺工时未分配边界：未分配量进报告勾稽差异，报告保持待核、审批被拒
  S9 工艺改造：旧表停用/旧系数封死，改造前后批次各自固定当时分配依据
  S10 后补表计：只产生带差异说明的新分配版本，旧依据标记被取代、历史可回查
前置：后端已启动（默认 http://127.0.0.1:8080），数据库为初始种子状态。
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8080'
API = BASE + '/api'

PASSED, FAILED = [], []


def req(method, path, body=None, token=None):
    url = API + path
    data = json.dumps(body).encode('utf-8') if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header('Content-Type', 'application/json')
    if token:
        r.add_header('X-Auth-Token', token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            raw = resp.read().decode('utf-8')
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {'error': raw}


def check(name, cond, detail=''):
    if cond:
        PASSED.append(name)
        print(f'  [PASS] {name}')
    else:
        FAILED.append((name, detail))
        print(f'  [FAIL] {name} :: {detail}')


def login(username, password):
    s, b = req('POST', '/auth/login', {'username': username, 'password': password})
    if s != 200:
        print(f'登录失败 {username}: {b}')
        sys.exit(1)
    return b['token']


def wait_run(token, run_id, timeout=40):
    for _ in range(timeout * 2):
        s, b = req('GET', f'/runs/{run_id}', token=token)
        if s == 200 and b['run']['status'] in ('COMPLETED', 'FAILED'):
            return b['run']
        time.sleep(0.5)
    raise RuntimeError(f'运行 {run_id} 超时未完成')


def find_impact(token, trigger_type, period, ref_keyword=None):
    s, impacts = req('GET', '/impacts', token=token)
    for im in impacts:
        if im['triggerType'] == trigger_type and im['period'] == period:
            if ref_keyword and ref_keyword not in im['triggerRef']:
                continue
            return im
    return None


def report_of(token, period):
    s, reports = req('GET', '/reports', token=token)
    for r in reports:
        if r['period'] == period:
            return r
    return None


def main():
    print(f'== 目标: {BASE}')
    admin = login('admin', 'admin123')
    analyst = login('analyst', 'analyst123')
    auditor = login('auditor', 'auditor123')
    finance = login('finance', 'finance123')

    # ---------- S0 基线 ----------
    print('\n[S0] 基线：种子数据核对')
    s, dash = req('GET', '/dashboard/summary', token=admin)
    pub = dash.get('latestPublished') or {}
    check('S0 已发布 2025-05 报告', pub.get('period') == '2025-05', str(pub))
    check('S0 披露总量 2503.18 tCO2e',
          pub and abs(float(pub['totalTco2e']) - 2503.18) < 0.01, str(pub.get('totalTco2e')))
    check('S0 范围一/二/三 = 70.92/917.26/1515',
          pub and abs(float(pub['totalScope1']) - 70.92) < 0.01
          and abs(float(pub['totalScope2']) - 917.26) < 0.01
          and abs(float(pub['totalScope3']) - 1515) < 0.01,
          str(pub))
    s, runs = req('GET', '/runs', token=admin)
    run_may = next((r for r in runs if r['period'] == '2025-05' and r['purpose'] == 'OFFICIAL'), None)
    check('S0 2025-05 正式运行已完成', run_may and run_may['status'] == 'COMPLETED')
    s, detail = req('GET', f"/runs/{run_may['id']}", token=admin)
    check('S0 运行绑定 6 个批准因子快照', len(detail['bindings']) == 6, str(len(detail['bindings'])))
    res0 = next(r for r in detail['results'] if r['energyType'] == 'GRID_ELEC' and r['plantId'] == 1
                and r['productLineId'] == 1)
    s, lin = req('GET', f"/results/{res0['id']}/lineage", token=admin)
    check('S0 血缘：结果可下钻到因子版本与来源行',
          lin['factorVersion']['factorCode'] == 'ELEC-CNEAST'
          and lin['sources'][0]['batchNo'] == 'B-2025-05-ELEC-SZ', json.dumps(lin)[:200])

    # ---------- S1 来源补录 ----------
    print('\n[S1] 来源补录 → 影响评估 → 重述')
    s, b = req('POST', '/batches', {
        'plantId': 1, 'category': 'FUEL', 'period': '2025-05',
        'rows': [{'productLineId': 1, 'energyType': 'DIESEL', 'quantity': 1500}]}, token=analyst)
    check('S1 补录批次已上传', s == 200, str(b))
    batch_s1 = b['id']
    s, b = req('POST', f'/batches/{batch_s1}/verify', token=auditor)
    check('S1 补录批次核验通过', s == 200 and b['status'] == 'VERIFIED', str(b))
    im = find_impact(admin, 'SOURCE_SUPPLEMENT', '2025-05')
    check('S1 自动生成来源补录影响评估', im is not None)
    s, imd = req('GET', f"/impacts/{im['id']}", token=admin)
    item = next((i for i in imd['items'] if i['scope'] == 'SCOPE1'), None)
    check('S1 影响指标：苏州/精密结构件/SCOPE1 +4.02',
          item and abs(float(item['diff']) - 4.02) < 0.001
          and abs(float(item['oldValue']) - 70.92) < 0.001, json.dumps(imd['items'])[:300])
    check('S1 影响项关联已发布报告', item and item['affectedReportId'] is not None)
    rest = imd['restatements'][0] if imd['restatements'] else None
    check('S1 自动生成重述建议(待处理)', rest and rest['status'] == 'PROPOSED', str(imd['restatements']))
    rep = report_of(admin, '2025-05')
    s, b = req('POST', f"/impacts/restatements/{rest['id']}/accept", token=finance)
    check('S1 接受重述 → 冻结 v2', s == 200 and b['newVersion']['versionNo'] == 2, str(b))
    s, b = req('POST', f"/reports/{rep['id']}/approve", token=finance)
    check('S1 重述版本审批通过', s == 200 and b['status'] == 'APPROVED', str(b))
    s, b = req('POST', f"/reports/{rep['id']}/publish", token=finance)
    check('S1 重述版本发布', s == 200 and b['status'] == 'PUBLISHED', str(b))
    s, rd = req('GET', f"/reports/{rep['id']}", token=admin)
    v1, v2 = rd['versions'][1], rd['versions'][0]
    check('S1 披露历史：v1 已重述 / v2 已发布',
          v1['status'] == 'RESTATED' and v2['status'] == 'PUBLISHED',
          f"v1={v1['status']} v2={v2['status']}")
    check('S1 v2 范围一 = 74.94（70.92+4.02）',
          abs(float(v2['totalScope1']) - 74.94) < 0.01, str(v2['totalScope1']))

    # ---------- S2 因子修订（运行中替换） ----------
    print('\n[S2] 因子修订：运行中替换因子仍绑定启动清单')
    s, run1 = req('POST', '/runs', {'period': '2025-06'}, token=analyst)
    check('S2 2025-06 正式运行启动', s == 200 and run1['status'] == 'RUNNING', str(run1))
    s, factors = req('GET', '/factors', token=admin)
    elec_east = next(f for f in factors if f['factor']['code'] == 'ELEC-CNEAST')
    s, v2draft = req('POST', f"/factors/{elec_east['factor']['id']}/versions",
                     {'value': 0.5420, 'validFrom': '2025-01-01', 'validTo': '2025-12-31'},
                     token=analyst)
    check('S2 新版电力因子草稿创建', s == 200, str(v2draft))
    s, b = req('POST', f"/factors/versions/{v2draft['id']}/approve", token=auditor)
    check('S2 运行中批准 v2（替换 v1）', s == 200 and b['status'] == 'APPROVED', str(b))
    run1 = wait_run(admin, run1['id'])
    s, d1 = req('GET', f"/runs/{run1['id']}", token=admin)
    vals = {r['factorValue'] for r in d1['results'] if r['energyType'] == 'GRID_ELEC'
            and r['plantId'] == 1 and r['status'] == 'OK'}
    check('S2 运行中替换因子：本次运行仍用启动时 v1=0.5566',
          vals == {0.5566} or vals == {'0.5566'}, str(vals))
    bound = {bnd['factorCode']: bnd['value'] for bnd in d1['bindings']}
    check('S2 绑定清单快照保留 v1 值', float(bound.get('ELEC-CNEAST', 0)) == 0.5566, str(bound))
    s, run2 = req('POST', '/runs', {'period': '2025-06'}, token=analyst)
    run2 = wait_run(admin, run2['id'])
    s, d2 = req('GET', f"/runs/{run2['id']}", token=admin)
    vals2 = {r['factorValue'] for r in d2['results'] if r['energyType'] == 'GRID_ELEC'
             and r['plantId'] == 1 and r['status'] == 'OK'}
    check('S2 新运行使用替换后 v2=0.5420', vals2 == {0.542} or vals2 == {'0.542'}, str(vals2))
    im_f = find_impact(admin, 'FACTOR_REVISION', '2025-05')
    check('S2 因子修订触发对 2025-05 已发布报告的影响评估', im_f is not None)
    s, imfd = req('GET', f"/impacts/{im_f['id']}", token=admin)
    diff_s2 = sum(float(i['diff']) for i in imfd['items']
                  if i['scope'] == 'SCOPE2' and i['plantId'] == 1)
    check('S2 影响差异：苏州 SCOPE2 合计 -16.06（0.5566→0.5420 × 110万kWh）',
          abs(diff_s2 + 16.06) < 0.01, f'diff={diff_s2}')
    rest_f = imfd['restatements'][0] if imfd['restatements'] else None
    check('S2 生成重述建议', rest_f and rest_f['status'] == 'PROPOSED')
    # 接受因子重述 → v3
    s, b = req('POST', f"/impacts/restatements/{rest_f['id']}/accept", token=finance)
    check('S2 接受重述 → 冻结 v3', s == 200 and b['newVersion']['versionNo'] == 3, str(b))
    rep = report_of(admin, '2025-05')
    req('POST', f"/reports/{rep['id']}/approve", token=finance)
    s, b = req('POST', f"/reports/{rep['id']}/publish", token=finance)
    check('S2 v3 发布', s == 200 and b['status'] == 'PUBLISHED', str(b))

    # ---------- S3 并发采购更正 ----------
    print('\n[S3] 并发采购更正：已核算采购量不可覆盖')
    s, batches = req('GET', '/batches', token=admin, )
    proc = next(b for b in batches if b['batchNo'] == 'B-2025-06-PROC-GP')
    s, pd = req('GET', f"/batches/{proc['id']}", token=admin)
    check('S3 采购批次行已被核算消耗', len(pd['consumedRowIds']) == 2, str(pd['consumedRowIds']))
    # 3a: 修订试图改小/改大已消耗凭证数量 → 409
    s, rev1 = req('POST', f"/batches/{proc['id']}/revise", {
        'plantId': 1, 'category': 'PROCUREMENT', 'period': '2025-06',
        'rows': [
            {'plantId': 1, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 520, 'voucherNo': 'V-1001'},
            {'plantId': 2, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 300, 'voucherNo': 'V-1002'}]},
        token=analyst)
    check('S3 修订批次已提交(待核验)', s == 200, str(rev1))
    s, b = req('POST', f"/batches/{rev1['id']}/verify", token=auditor)
    check('S3 覆盖已核算采购量被拒绝(409)', s == 409, f's={s} {b}')
    s, b = req('GET', f"/batches/{rev1['id']}", token=admin)
    check('S3 违规修订标记为冲突', b['batch']['status'] == 'CONFLICT', str(b['batch']['status']))
    s, b = req('GET', f"/batches/{proc['id']}", token=admin)
    row_v1001 = next(r for r in b['rows'] if r['voucherNo'] == 'V-1001')
    check('S3 原采购量未被覆盖（V-1001 仍 500）',
          float(row_v1001['quantity']) == 500.0, str(row_v1001['quantity']))
    # 3b: 合规修订（不动已消耗行，新增凭证行）→ 通过
    s, rev2 = req('POST', f"/batches/{proc['id']}/revise", {
        'plantId': 1, 'category': 'PROCUREMENT', 'period': '2025-06',
        'rows': [
            {'plantId': 1, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 500, 'voucherNo': 'V-1001'},
            {'plantId': 2, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 300, 'voucherNo': 'V-1002'},
            {'plantId': 2, 'productLineId': 2, 'energyType': 'PURCH_STEEL', 'quantity': 80, 'voucherNo': 'V-1003'}]},
        token=analyst)
    s, b = req('POST', f"/batches/{rev2['id']}/verify", token=auditor)
    check('S3 合规修订核验通过', s == 200 and b['status'] == 'VERIFIED', str(b))
    s, b = req('GET', f"/batches/{proc['id']}", token=admin)
    check('S3 原批次被取代', b['batch']['status'] == 'SUPERSEDED', str(b['batch']['status']))
    # 3c: 同一批次并发修订 → 第二个 409
    cur = rev2['id']
    s, ra = req('POST', f"/batches/{cur}/revise", {
        'plantId': 1, 'category': 'PROCUREMENT', 'period': '2025-06',
        'rows': [
            {'plantId': 1, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 500, 'voucherNo': 'V-1001'},
            {'plantId': 2, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 300, 'voucherNo': 'V-1002'},
            {'plantId': 2, 'productLineId': 2, 'energyType': 'PURCH_STEEL', 'quantity': 80, 'voucherNo': 'V-1003'}]},
        token=analyst)
    check('S3 工厂A修订提交成功', s == 200, str(ra))
    s, rb = req('POST', f"/batches/{cur}/revise", {
        'plantId': 2, 'category': 'PROCUREMENT', 'period': '2025-06',
        'rows': [
            {'plantId': 1, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 500, 'voucherNo': 'V-1001'},
            {'plantId': 2, 'productLineId': 1, 'energyType': 'PURCH_STEEL', 'quantity': 300, 'voucherNo': 'V-1002'},
            {'plantId': 2, 'productLineId': 2, 'energyType': 'PURCH_STEEL', 'quantity': 80, 'voucherNo': 'V-1003'}]},
        token=analyst)
    check('S3 工厂B并发修订同一批次被拒绝(409)', s == 409, f's={s} {rb}')
    req('POST', f"/batches/{ra['id']}/reject", {'reason': '清理演示数据'}, token=auditor)

    # ---------- S4 勾稽不平保持待核 ----------
    print('\n[S4] 勾稽不平：报告保持待核，不得归零')
    s, b = req('POST', '/reports', {
        'title': '2025年6月集团碳排放披露报告', 'period': '2025-06', 'runId': run2['id']},
        token=analyst)
    check('S4 2025-06 报告创建', s == 200, str(b))
    rep6 = b
    check('S4 报告保持待核(RECONCILE_FAILED)', b['status'] == 'RECONCILE_FAILED', str(b['status']))
    s, rd6 = req('GET', f"/reports/{rep6['id']}", token=admin)
    ver6 = rd6['versions'][0]
    check('S4 勾稽差异=未分摊公共用电 54.2 tCO2e',
          ver6['reconcileStatus'] == 'MISMATCH' and abs(float(ver6['reconcileDiff']) - 54.2) < 0.01,
          f"{ver6['reconcileStatus']} diff={ver6['reconcileDiff']}")
    check('S4 含 1 项缺因子待核指标（成都天然气）', ver6['pendingCount'] == 1, str(ver6['pendingCount']))
    s, b = req('POST', f"/reports/{rep6['id']}/approve", token=finance)
    check('S4 待核报告审批被拒绝(409)', s == 409, f's={s}')
    s, b = req('POST', f"/reports/{rep6['id']}/publish", token=finance)
    check('S4 待核报告发布被拒绝(409)', s == 409, f's={s}')
    # 修复1：批准西南天然气因子 → 消除待核指标
    s, factors = req('GET', '/factors', token=admin)
    ng_west = next(f for f in factors if f['factor']['code'] == 'NG-CNWEST')
    draft_v = next(v for v in ng_west['versions'] if v['status'] == 'DRAFT')
    s, b = req('POST', f"/factors/versions/{draft_v['id']}/approve", token=auditor)
    check('S4 西南天然气因子批准', s == 200, str(b))
    # 修复2：修订电力批次，把公共用电分摊到产品线
    elec6 = next(x for x in req('GET', '/batches', token=admin)[1]
                 if x['batchNo'] == 'B-2025-06-ELEC-SZ')
    s, rev_e = req('POST', f"/batches/{elec6['id']}/revise", {
        'plantId': 1, 'category': 'ELECTRICITY', 'period': '2025-06',
        'rows': [
            {'productLineId': 1, 'energyType': 'GRID_ELEC', 'quantity': 800000},
            {'productLineId': 2, 'energyType': 'GRID_ELEC', 'quantity': 500000}]},
        token=analyst)
    s, b = req('POST', f"/batches/{rev_e['id']}/verify", token=auditor)
    check('S4 公共用电分摊修订核验通过', s == 200 and b['status'] == 'VERIFIED', str(b))
    # 修复3：重新运行并冻结新版本
    s, run3 = req('POST', '/runs', {'period': '2025-06'}, token=analyst)
    run3 = wait_run(admin, run3['id'])
    s, d3 = req('GET', f"/runs/{run3['id']}", token=admin)
    pend3 = [r for r in d3['results'] if r['status'] == 'PENDING_FACTOR']
    check('S4 重算后无待核指标', len(pend3) == 0, str(len(pend3)))
    s, b = req('POST', f"/reports/{rep6['id']}/freeze", {'runId': run3['id'], 'note': '修复后重冻结'},
               token=analyst)
    check('S4 修复后冻结 v2 勾稽一致', s == 200 and b['reconcileStatus'] == 'OK', str(b))
    s, b = req('POST', f"/reports/{rep6['id']}/approve", token=finance)
    check('S4 修复后审批通过', s == 200 and b['status'] == 'APPROVED', str(b))
    s, b = req('POST', f"/reports/{rep6['id']}/publish", token=finance)
    check('S4 修复后发布成功', s == 200 and b['status'] == 'PUBLISHED', str(b))

    # ---------- S5 已发布报告发现来源缺失 ----------
    print('\n[S5] 已发布报告发现来源缺失 → 重述建议，不覆盖已发布报告')
    s, rd = req('GET', f"/reports/{rep['id']}", token=admin)
    pub_v = rd['versions'][0]
    check('S5 当前发布版本 v3（范围一 74.94）',
          pub_v['versionNo'] == 3 and abs(float(pub_v['totalScope1']) - 74.94) < 0.01,
          f"v{pub_v['versionNo']} s1={pub_v['totalScope1']}")
    fuel_may = next(x for x in req('GET', '/batches', token=admin)[1]
                    if x['batchNo'] == 'B-2025-05-FUEL-SZ')
    s, b = req('POST', f"/batches/{fuel_may['id']}/void",
               {'reason': '审计发现电表/气表原始记录缺失'}, token=auditor)
    check('S5 来源批次作废', s == 200 and b['status'] == 'VOID', str(b))
    im_m = find_impact(admin, 'SOURCE_MISSING', '2025-05')
    check('S5 自动生成来源缺失影响评估', im_m is not None)
    s, immd = req('GET', f"/impacts/{im_m['id']}", token=admin)
    item_m = next((i for i in immd['items'] if i['scope'] == 'SCOPE1'), None)
    check('S5 差异说明：苏州 SCOPE1 -70.92（缺失燃料数据）',
          item_m and abs(float(item_m['diff']) + 70.92) < 0.01, json.dumps(immd['items'])[:300])
    rest_m = immd['restatements'][0] if immd['restatements'] else None
    check('S5 生成重述建议(待处理)', rest_m and rest_m['status'] == 'PROPOSED')
    # 已发布报告内容未被覆盖
    s, rd2 = req('GET', f"/reports/{rep['id']}", token=admin)
    still = next(v for v in rd2['versions'] if v['id'] == pub_v['id'])
    check('S5 已发布 v3 内容未被覆盖（仍 74.94 / PUBLISHED）',
          still['status'] == 'PUBLISHED' and abs(float(still['totalScope1']) - 74.94) < 0.01,
          f"{still['status']} {still['totalScope1']}")
    s, b = req('POST', f"/impacts/restatements/{rest_m['id']}/accept", token=finance)
    check('S5 接受重述 → 冻结 v4', s == 200 and b['newVersion']['versionNo'] == 4, str(b))
    req('POST', f"/reports/{rep['id']}/approve", token=finance)
    s, b = req('POST', f"/reports/{rep['id']}/publish", token=finance)
    check('S5 v4 发布', s == 200 and b['status'] == 'PUBLISHED', str(b))
    s, rd3 = req('GET', f"/reports/{rep['id']}", token=admin)
    statuses = {v['versionNo']: v['status'] for v in rd3['versions']}
    v4 = rd3['versions'][0]
    check('S5 披露历史完整：v1-v3 已重述，v4 已发布',
          statuses.get(1) == 'RESTATED' and statuses.get(2) == 'RESTATED'
          and statuses.get(3) == 'RESTATED' and statuses.get(4) == 'PUBLISHED', str(statuses))
    check('S5 v4 范围一 = 4.02（仅剩补录柴油）',
          abs(float(v4['totalScope1']) - 4.02) < 0.01, str(v4['totalScope1']))

    # ---------- S6 共用资源守恒与证据等级 ----------
    print('\n[S6] 共用资源分配：守恒核对、证据等级与平衡区间')
    s, allocs = req('GET', '/allocations/runs?period=2025-07&energyType=STEAM', token=admin)
    h7 = next(a for a in allocs if a['method'] == 'HIERARCHY' and a['status'] == 'FIXED')
    b7 = next(a for a in allocs if a['method'] == 'BALANCE')
    check('S6 等级法蒸汽守恒：1000 = 已分配1000 + 未分配0',
          float(h7['totalQty']) == 1000 and float(h7['allocatedQty']) == 1000
          and float(h7['unallocatedQty']) == 0 and abs(float(h7['conservationDiff'])) < 0.001,
          str(h7))
    s, hd = req('GET', f"/allocations/runs/{h7['id']}", token=admin)
    recs = {rv['record']['productLineId']: rv['record'] for rv in hd['records']}
    check('S6 等级法 L1=400 子表证据 / L2=600 校准系数证据',
          float(recs[1]['qty']) == 400 and recs[1]['evidenceGrade'] == 'SUB_METER'
          and float(recs[2]['qty']) == 600 and recs[2]['evidenceGrade'] == 'COEF',
          json.dumps(recs, ensure_ascii=False))
    grades = {e['evidenceGrade'] for e in hd['evidence']}
    check('S6 证据快照含子表与系数两类，可回查复算',
          {'SUB_METER', 'COEF'}.issubset(grades), str(grades))
    check('S6 平衡法可行且零未分配，总区间=[984,1016]',
          b7['feasible'] is True and float(b7['unallocatedQty']) == 0
          and float(b7['uncertaintyLow']) == 984 and float(b7['uncertaintyHigh']) == 1016, str(b7))
    s, bd = req('GET', f"/allocations/runs/{b7['id']}", token=admin)
    brecs = {rv['record']['productLineId']: rv['record'] for rv in bd['records']}
    check('S6 平衡区间：L1∈[392,408]、L2∈[592,608]（子表±2%与产能约束联立）',
          float(brecs[1]['qtyLow']) == 392 and float(brecs[1]['qtyHigh']) == 408
          and float(brecs[2]['qtyLow']) == 592 and float(brecs[2]['qtyHigh']) == 608,
          json.dumps(brecs, ensure_ascii=False))
    check('S6 两种口径同为正式口径（均无主辅标记，状态可固定/可并列查询）',
          h7['method'] == 'HIERARCHY' and b7['method'] == 'BALANCE')
    # 第三级证据：成都压缩空气 L2 无系数有工时 → 按工时占比分摊总表余量
    cd_ca = next(a for a in req('GET', '/allocations/runs?period=2025-07&energyType=COMP_AIR', token=admin)[1]
                 if a['plantId'] == 2 and a['status'] == 'FIXED')
    s, cdd = req('GET', f"/allocations/runs/{cd_ca['id']}", token=admin)
    cd_recs = {rv['record']['productLineId']: rv['record'] for rv in cdd['records']}
    check('S6 工时等级：L1=2500 子表 / L2=3500 工时占比分摊（WORK_HOUR），守恒为0',
          float(cd_recs[1]['qty']) == 2500 and cd_recs[1]['evidenceGrade'] == 'SUB_METER'
          and float(cd_recs[2]['qty']) == 3500 and cd_recs[2]['evidenceGrade'] == 'WORK_HOUR'
          and abs(float(cd_ca['conservationDiff'])) < 0.001 and float(cd_ca['unallocatedQty']) == 0,
          json.dumps(cd_recs, ensure_ascii=False))
    check('S6 工时证据快照含工时记录单与占比说明，非产量平均',
          any(e['evidenceGrade'] == 'WORK_HOUR' and '工时占比' in (e['detail'] or '')
              for e in cdd['evidence']), json.dumps(cdd['evidence'], ensure_ascii=False)[:300])
    run7 = next(r for r in req('GET', '/runs', token=admin)[1]
                if r['period'] == '2025-07' and r['purpose'] == 'OFFICIAL')
    s, rd7 = req('GET', f"/runs/{run7['id']}", token=admin)
    st_results = [r for r in rd7['results'] if r['energyType'] == 'STEAM']
    check('S6 核算结果绑定固定分配运行，蒸汽活动量合计=总表1000',
          all(r['allocationRunId'] == h7['id'] for r in st_results)
          and abs(sum(float(r['activityQty']) for r in st_results) - 1000) < 0.001,
          json.dumps(st_results)[:300])
    res_l1 = next(r for r in st_results if r['productLineId'] == 1)
    s, lin = req('GET', f"/results/{res_l1['id']}/lineage", token=admin)
    check('S6 血缘可下钻到分配依据与证据快照',
          lin.get('allocation') is not None
          and lin['allocation']['allocationRun']['id'] == h7['id']
          and len(lin['allocation']['evidence']) >= 2, json.dumps(lin.get('allocation', {}))[:200])

    # ---------- S7 子表矛盾：申领超总量阻断 ----------
    print('\n[S7] 子表矛盾：两个产品线申领合计超过总表 → 阻断')
    for m in [('M-T-ST-09', 'MAIN', None, 500, 1.0),
              ('M-T-ST-L1-09', 'SUB', 1, 300, 2.0),
              ('M-T-ST-L2-09', 'SUB', 2, 250, 2.0)]:
        s, b = req('POST', '/allocations/meters', {
            'meterNo': m[0], 'plantId': 1, 'energyType': 'STEAM', 'role': m[1],
            'productLineId': m[2], 'errorPct': m[4], 'calibrationRef': 'CAL-2509'}, token=analyst)
        check(f"S7 表计 {m[0]} 登记", s == 200, str(b))
    meters9 = req('GET', '/allocations/meters?plantId=1&energyType=STEAM', token=admin)[1]
    mid = {m['meterNo']: m['id'] for m in meters9}
    s, b = req('POST', '/batches', {
        'plantId': 1, 'category': 'UTILITY', 'period': '2025-09',
        'rows': [
            {'energyType': 'STEAM', 'quantity': 500, 'meterId': mid['M-T-ST-09']},
            {'productLineId': 1, 'energyType': 'STEAM', 'quantity': 300, 'meterId': mid['M-T-ST-L1-09']},
            {'productLineId': 2, 'energyType': 'STEAM', 'quantity': 250, 'meterId': mid['M-T-ST-L2-09']}]},
        token=analyst)
    check('S7 矛盾读数批次已上传', s == 200, str(b))
    s, b = req('POST', f"/batches/{b['id']}/verify", token=auditor)
    check('S7 矛盾批次核验通过', s == 200 and b['status'] == 'VERIFIED', str(b))
    s, b = req('POST', '/allocations/runs', {
        'plantId': 1, 'energyType': 'STEAM', 'period': '2025-09',
        'method': 'HIERARCHY', 'fix': False}, token=analyst)
    check('S7 子表申领550 > 总表500 被阻断(409)', s == 409 and '超过总表' in b.get('error', ''), f's={s} {b}')
    s, runs9 = req('GET', '/allocations/runs?period=2025-09', token=admin)
    check('S7 阻断后不产生任何分配结果（无超总量足迹）', len(runs9) == 0, str(runs9))

    # ---------- S8 缺工时未分配边界 ----------
    print('\n[S8] 缺工时：未分配量进入勾稽差异，报告保持待核')
    ca7 = next(a for a in req('GET', '/allocations/runs?period=2025-07&energyType=COMP_AIR', token=admin)[1]
               if a['status'] == 'FIXED' and a['plantId'] == 1)
    check('S8 压缩空气 L2 缺工时：5000 Nm3 保留未分配',
          float(ca7['allocatedQty']) == 3000 and float(ca7['unallocatedQty']) == 5000
          and abs(float(ca7['conservationDiff'])) < 0.001, str(ca7))
    s, cad = req('GET', f"/allocations/runs/{ca7['id']}", token=admin)
    check('S8 未分配部分留有证据说明（缺工时，不按产量平均）',
          any(e['evidenceGrade'] == 'UNALLOCATED' and '缺工时' in (e['detail'] or '')
              for e in cad['evidence']), json.dumps(cad['evidence'], ensure_ascii=False)[:300])
    s, rep7 = req('POST', '/reports', {
        'title': '2025年7月集团碳排放披露报告', 'period': '2025-07', 'runId': run7['id']},
        token=analyst)
    check('S8 含未分配量的报告保持待核', s == 200 and rep7['status'] == 'RECONCILE_FAILED', str(rep7))
    ver7 = req('GET', f"/reports/{rep7['id']}", token=admin)[1]['versions'][0]
    check('S8 勾稽差异=未分配压缩空气 0.4 tCO2e（5000×0.08/1000）',
          ver7['reconcileStatus'] == 'MISMATCH' and abs(float(ver7['reconcileDiff']) - 0.4) < 0.001,
          str(ver7['reconcileDiff']))
    s, b = req('POST', f"/reports/{rep7['id']}/approve", token=finance)
    check('S8 待核报告审批被拒(409)', s == 409, f's={s}')
    # 修复：补登 L2 压缩空气工时（417h × 系数12 = 5004，超余量5000 等比缩放为5000）
    s, b = req('POST', '/allocations/work-hours', {
        'plantId': 1, 'productLineId': 2, 'energyType': 'COMP_AIR', 'period': '2025-07',
        'hours': 417, 'sourceRef': 'WH-2507-04'}, token=analyst)
    check('S8 补登缺漏工时', s == 200, str(b))
    s, b = req('POST', '/allocations/runs', {
        'plantId': 1, 'energyType': 'COMP_AIR', 'period': '2025-07',
        'method': 'HIERARCHY', 'fix': True, 'changeNote': '补登L2工时后重新分配，未分配量清零'},
        token=analyst)
    check('S8 重新分配后未分配清零、守恒成立',
          s == 200 and float(b['unallocatedQty']) == 0 and abs(float(b['conservationDiff'])) < 0.001, str(b))
    s, run7b = req('POST', '/runs', {'period': '2025-07'}, token=analyst)
    run7b = wait_run(admin, run7b['id'])
    s, b = req('POST', f"/reports/{rep7['id']}/freeze", {'runId': run7b['id'], 'note': '补齐工时后重冻结'},
               token=analyst)
    check('S8 修复后冻结版本勾稽一致', s == 200 and b['reconcileStatus'] == 'OK', str(b))
    req('POST', f"/reports/{rep7['id']}/approve", token=finance)
    s, b = req('POST', f"/reports/{rep7['id']}/publish", token=finance)
    check('S8 修复后报告发布成功', s == 200 and b['status'] == 'PUBLISHED', str(b))

    # ---------- S9 工艺改造：批次固定当时分配依据 ----------
    print('\n[S9] 工艺改造：改造前后批次各自固定当时依据')
    meters = req('GET', '/allocations/meters?plantId=1&energyType=STEAM', token=admin)[1]
    old_main = next(m for m in meters if m['meterNo'] == 'M-SZ-ST-01')
    old_sub = next(m for m in meters if m['meterNo'] == 'M-SZ-ST-L1')
    new_main = next(m for m in meters if m['meterNo'] == 'M-SZ-ST-02')
    check('S9 改造后旧总表/旧子表停用，新表在用',
          old_main['status'] == 'RETIRED' and old_sub['status'] == 'RETIRED'
          and new_main['status'] == 'ACTIVE', json.dumps([old_main['status'], new_main['status']]))
    coefs = req('GET', '/allocations/coefficients?plantId=1&energyType=STEAM', token=admin)[1]
    old_coef = next(c for c in coefs if c['validTo'] == '2025-07')
    new_coef = next(c for c in coefs if c['validFrom'] == '2025-08' and c['status'] == 'ACTIVE')
    check('S9 旧系数适用期封死至2025-07并停用，新系数2025-08起生效',
          old_coef['status'] == 'RETIRED' and new_coef['coefficient'] == 0.8, str(old_coef))
    h7_now = req('GET', f"/allocations/runs/{h7['id']}", token=admin)[1]['run']
    check('S9 改造前2025-07批次依据仍为已固定且数值不变（L1=400/L2=600）',
          h7_now['status'] == 'FIXED' and float(h7_now['allocatedQty']) == 1000, str(h7_now['status']))
    a8 = next(a for a in req('GET', '/allocations/runs?period=2025-08&energyType=STEAM', token=admin)[1]
              if a['status'] == 'FIXED' and a['plantId'] == 1)
    check('S9 改造后2025-08批次固定新依据（子表400+280，未分配270）并带改造说明',
          float(a8['allocatedQty']) == 680 and float(a8['unallocatedQty']) == 270
          and '工艺改造' in (a8.get('changeNote') or ''), str(a8))
    run8 = next(r for r in req('GET', '/runs', token=admin)[1]
                if r['period'] == '2025-08' and r['purpose'] == 'OFFICIAL')
    st8 = [r for r in req('GET', f"/runs/{run8['id']}", token=admin)[1]['results']
           if r['energyType'] == 'STEAM']
    check('S9 改造后核算绑定2025-08新依据，与改造前运行互不影响',
          all(r['allocationRunId'] == a8['id'] for r in st8)
          and abs(sum(float(r['activityQty']) for r in st8) - 950) < 0.001, json.dumps(st8)[:200])

    # ---------- S10 后补表计：只产生带差异说明的新版本 ----------
    print('\n[S10] 后补表计：新足迹版本 + 差异说明，旧依据保留可回查')
    s, b = req('POST', '/allocations/meters', {
        'meterNo': 'M-SZ-ST-L2B-07', 'plantId': 1, 'energyType': 'STEAM', 'role': 'SUB',
        'productLineId': 2, 'errorPct': 2.0, 'calibrationRef': 'CAL-2507-BACKFILL'}, token=analyst)
    check('S10 后补子表登记', s == 200, str(b))
    backfill_meter = b['id']
    s, b = req('POST', '/batches', {
        'plantId': 1, 'category': 'UTILITY', 'period': '2025-07',
        'rows': [{'productLineId': 2, 'energyType': 'STEAM', 'quantity': 590,
                  'meterId': backfill_meter}]}, token=analyst)
    check('S10 后补读数批次已上传', s == 200, str(b))
    s, b = req('POST', f"/batches/{b['id']}/verify", token=auditor)
    check('S10 后补批次核验通过', s == 200 and b['status'] == 'VERIFIED', str(b))
    s, new_alloc = req('POST', '/allocations/runs', {
        'plantId': 1, 'energyType': 'STEAM', 'period': '2025-07', 'method': 'HIERARCHY',
        'fix': True, 'changeNote': '后补L2子表读数590，产生新分配版本；原系数推算版本保留差异说明'},
        token=analyst)
    check('S10 新分配版本生成并固定', s == 200 and new_alloc['status'] == 'FIXED', str(new_alloc))
    s, nad = req('GET', f"/allocations/runs/{new_alloc['id']}", token=admin)
    nrecs = {rv['record']['productLineId']: rv['record'] for rv in nad['records']}
    check('S10 新版本：L1=400/L2=590 全部子表证据，未分配10，守恒成立',
          float(nrecs[1]['qty']) == 400 and float(nrecs[2]['qty']) == 590
          and nrecs[2]['evidenceGrade'] == 'SUB_METER'
          and float(new_alloc['unallocatedQty']) == 10, json.dumps(nrecs, ensure_ascii=False))
    s, oldd = req('GET', f"/allocations/runs/{h7['id']}", token=admin)
    check('S10 旧依据标记被取代并指向新版本，历史证据仍可回查',
          oldd['run']['status'] == 'SUPERSEDED'
          and oldd['run']['supersededByRunId'] == new_alloc['id']
          and len(oldd['records']) == 2 and len(oldd['evidence']) >= 2,
          json.dumps(oldd['run'], ensure_ascii=False)[:250])
    s, run7c = req('POST', '/runs', {'period': '2025-07'}, token=analyst)
    run7c = wait_run(admin, run7c['id'])
    st7c = [r for r in req('GET', f"/runs/{run7c['id']}", token=admin)[1]['results']
            if r['energyType'] == 'STEAM' and r['productLineId'] == 2]
    check('S10 新核算运行采用后补表计版本（L2=590）',
          float(st7c[0]['activityQty']) == 590 and st7c[0]['allocationRunId'] == new_alloc['id'],
          json.dumps(st7c)[:200])
    rd_after = req('GET', f"/reports/{rep7['id']}", token=admin)[1]
    pub_ver = next(v for v in rd_after['versions'] if v['status'] == 'PUBLISHED')
    check('S10 已发布报告内容不被后补表计覆盖（仍为发布状态，新版本须经重述流程）',
          pub_ver is not None and len(rd_after['versions']) == 2,
          json.dumps([v['status'] for v in rd_after['versions']]))

    # ---------- 汇总 ----------
    print('\n========================================')
    print(f'通过 {len(PASSED)} 项，失败 {len(FAILED)} 项')
    if FAILED:
        for name, detail in FAILED:
            print(f'  失败: {name} :: {detail}')
        sys.exit(1)
    print('全部验收场景回放通过 ✔')


if __name__ == '__main__':
    main()
