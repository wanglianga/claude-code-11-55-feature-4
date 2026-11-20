<template>
  <div>
    <div class="page-card">
      <h3 class="page-title">共用资源分配（蒸汽 / 压缩空气）— 可回查的分配证据链</h3>
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
                title="等级法（子表→校准系数→工时）与平衡法（总表+误差界+产能平衡求解）同为正式主口径；所有结果始终与总表总量核对，输出已分配、未分配及不确定范围。" />
      <el-tabs v-model="tab">
        <!-- ================= 分配运行 ================= -->
        <el-tab-pane label="分配运行" name="runs">
          <div class="toolbar">
            <el-input v-model="filters.period" placeholder="期间 YYYY-MM" style="width:130px" clearable @change="loadRuns" />
            <el-select v-model="filters.energyType" placeholder="资源" style="width:140px" clearable @change="loadRuns">
              <el-option label="共用蒸汽" value="STEAM" />
              <el-option label="共用压缩空气" value="COMP_AIR" />
            </el-select>
            <el-button @click="loadRuns">查询</el-button>
            <el-button type="primary" @click="openCreate">新建分配运行</el-button>
          </div>
          <el-table :data="runs" size="small" border v-loading="loadingRuns">
            <el-table-column prop="runNo" label="分配单号" width="150" />
            <el-table-column label="工厂" width="100">
              <template #default="{ row }">{{ store.plantName(row.plantId) }}</template>
            </el-table-column>
            <el-table-column label="资源" width="110">
              <template #default="{ row }">{{ store.energyLabel(row.energyType) }}</template>
            </el-table-column>
            <el-table-column prop="period" label="期间" width="80" />
            <el-table-column label="口径" width="150">
              <template #default="{ row }">
                <el-tag size="small" :type="row.method === 'HIERARCHY' ? 'primary' : 'success'">
                  {{ row.method === 'HIERARCHY' ? '等级法' : '平衡求解法' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="总表/已分配/未分配" width="240">
              <template #default="{ row }">
                {{ fmtNum(row.totalQty) }} = {{ fmtNum(row.allocatedQty) }}
                <b :class="row.unallocatedQty > 0 ? 'diff-pos' : ''">+ {{ fmtNum(row.unallocatedQty) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="可行区间" width="170">
              <template #default="{ row }">
                <span v-if="row.uncertaintyLow != null">
                  [{{ fmtNum(row.uncertaintyLow) }}, {{ fmtNum(row.uncertaintyHigh) }}]
                </span>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="守恒" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="Math.abs(Number(row.conservationDiff)) <= 0.001 ? 'success' : 'danger'">
                  {{ Math.abs(Number(row.conservationDiff)) <= 0.001 ? '平衡' : '差异' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="allocStatusType(row.status)">{{ allocStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button size="small" link type="primary" @click="openDetail(row)">证据详情</el-button>
                <el-button v-if="row.status === 'DRAFT'" size="small" link type="success" @click="doFix(row)">固定</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ================= 表计 ================= -->
        <el-tab-pane label="表计登记" name="meters">
          <div class="toolbar">
            <el-button type="primary" @click="meterDlg.visible = true">登记表计</el-button>
            <span class="hint">总表=共用资源总量（守恒锚点）；子表=产品线局部计量，登记校准误差界</span>
          </div>
          <el-table :data="meters" size="small" border>
            <el-table-column prop="meterNo" label="表计编号" width="140" />
            <el-table-column label="工厂" width="100">
              <template #default="{ row }">{{ store.plantName(row.plantId) }}</template>
            </el-table-column>
            <el-table-column label="资源" width="110">
              <template #default="{ row }">{{ store.energyLabel(row.energyType) }}</template>
            </el-table-column>
            <el-table-column label="角色" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.role === 'MAIN' ? 'warning' : 'info'">
                  {{ row.role === 'MAIN' ? '总表' : '子表' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="归属产品线" width="120">
              <template #default="{ row }">{{ store.lineName(row.productLineId) }}</template>
            </el-table-column>
            <el-table-column label="误差界" width="90">
              <template #default="{ row }">±{{ row.errorPct }}%</template>
            </el-table-column>
            <el-table-column prop="calibrationRef" label="校准证书" width="140" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ row.status === 'ACTIVE' ? '在用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button v-if="row.status === 'ACTIVE'" size="small" link type="danger"
                           @click="retireMeter(row)">停用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ================= 工程系数 ================= -->
        <el-tab-pane label="工程系数" name="coefs">
          <div class="toolbar">
            <el-button type="primary" @click="coefDlg.visible = true">登记系数</el-button>
            <span class="hint">单位工时资源消耗量；工艺改造后旧系数以适用期封死，新系数另起一行</span>
          </div>
          <el-table :data="coefs" size="small" border>
            <el-table-column label="工厂" width="100">
              <template #default="{ row }">{{ store.plantName(row.plantId) }}</template>
            </el-table-column>
            <el-table-column label="产品线" width="120">
              <template #default="{ row }">{{ store.lineName(row.productLineId) }}</template>
            </el-table-column>
            <el-table-column label="资源" width="110">
              <template #default="{ row }">{{ store.energyLabel(row.energyType) }}</template>
            </el-table-column>
            <el-table-column prop="coefficient" label="单位工时系数" width="120" />
            <el-table-column prop="basis" label="测算来源" show-overflow-tooltip />
            <el-table-column label="适用期" width="160">
              <template #default="{ row }">{{ row.validFrom }} ~ {{ row.validTo || '至今' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ row.status === 'ACTIVE' ? '在用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button v-if="row.status === 'ACTIVE'" size="small" link type="danger"
                           @click="retireCoef(row)">停用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ================= 工时 ================= -->
        <el-tab-pane label="工时台账" name="hours">
          <div class="toolbar">
            <el-button type="primary" @click="hourDlg.visible = true">登记工时</el-button>
            <span class="hint">工厂 × 产品线 × 期间唯一；缺工时的产品线在等级法下保留未分配量</span>
          </div>
          <el-table :data="hours" size="small" border>
            <el-table-column label="工厂" width="120">
              <template #default="{ row }">{{ store.plantName(row.plantId) }}</template>
            </el-table-column>
            <el-table-column label="产品线" width="140">
              <template #default="{ row }">{{ store.lineName(row.productLineId) }}</template>
            </el-table-column>
            <el-table-column label="资源" width="110">
              <template #default="{ row }">{{ store.energyLabel(row.energyType) }}</template>
            </el-table-column>
            <el-table-column prop="period" label="期间" width="100" />
            <el-table-column prop="hours" label="工时" width="120" />
            <el-table-column prop="sourceRef" label="工时记录单号" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 新建分配运行 -->
    <el-dialog v-model="createDlg.visible" title="新建分配运行" width="520px">
      <el-form label-width="100px">
        <el-form-item label="工厂">
          <el-select v-model="createDlg.plantId" style="width:260px">
            <el-option v-for="p in store.plants" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="共用资源">
          <el-select v-model="createDlg.energyType" style="width:260px">
            <el-option label="共用蒸汽 STEAM" value="STEAM" />
            <el-option label="共用压缩空气 COMP_AIR" value="COMP_AIR" />
          </el-select>
        </el-form-item>
        <el-form-item label="期间">
          <el-input v-model="createDlg.period" placeholder="YYYY-MM，如 2025-07" style="width:260px" />
        </el-form-item>
        <el-form-item label="主口径">
          <el-radio-group v-model="createDlg.method">
            <el-radio value="HIERARCHY">等级法（子表→系数→工时）</el-radio>
            <el-radio value="BALANCE">平衡求解法（区间）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="直接固定">
          <el-switch v-model="createDlg.fix" />
          <span class="hint" style="margin-left:8px">固定后成为批次核算依据，不可改写</span>
        </el-form-item>
        <el-form-item label="差异说明">
          <el-input v-model="createDlg.changeNote" type="textarea" rows="2"
                    placeholder="后补表计/工艺改造时填写差异说明（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDlg.saving" @click="submitCreate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 证据详情抽屉 -->
    <el-drawer v-model="detailDlg.visible" :title="`分配证据 ${detailDlg.data?.run?.runNo || ''}`" size="720px">
      <template v-if="detailDlg.data">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="口径">
            {{ detailDlg.data.run.method === 'HIERARCHY' ? '等级法（子表→系数→工时）' : '平衡求解法（总表+误差界+产能）' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="allocStatusType(detailDlg.data.run.status)">
              {{ allocStatusLabel(detailDlg.data.run.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="工厂/资源/期间">
            {{ store.plantName(detailDlg.data.run.plantId) }} /
            {{ store.energyLabel(detailDlg.data.run.energyType) }} /
            {{ detailDlg.data.run.period }}
          </el-descriptions-item>
          <el-descriptions-item label="固定人/时间">
            {{ detailDlg.data.run.fixedBy || '-' }} · {{ fmtTime(detailDlg.data.run.fixedAt) }}
          </el-descriptions-item>
        </el-descriptions>

        <h4>守恒核对（始终与共用资源总量核对）</h4>
        <el-descriptions :column="3" border size="small" :title="`单位 ${detailDlg.data.conservation.unit}`">
          <el-descriptions-item label="总表总量">{{ fmtNum(detailDlg.data.conservation.totalQty) }}</el-descriptions-item>
          <el-descriptions-item label="已分配">{{ fmtNum(detailDlg.data.conservation.allocatedQty) }}</el-descriptions-item>
          <el-descriptions-item label="未分配">
            <b :class="detailDlg.data.conservation.unallocatedQty > 0 ? 'diff-pos' : ''">
              {{ fmtNum(detailDlg.data.conservation.unallocatedQty) }}
            </b>
          </el-descriptions-item>
          <el-descriptions-item label="已分配+未分配">
            {{ fmtNum(detailDlg.data.conservation.sumCheck) }}
          </el-descriptions-item>
          <el-descriptions-item label="守恒差异">
            <el-tag size="small" :type="detailDlg.data.conservation.balanced ? 'success' : 'danger'">
              {{ fmtNum(detailDlg.data.conservation.diff) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="不确定范围">
            [{{ fmtNum(detailDlg.data.conservation.uncertaintyLow) }},
            {{ fmtNum(detailDlg.data.conservation.uncertaintyHigh) }}]
          </el-descriptions-item>
        </el-descriptions>

        <h4>产品线分配结果（点值 / 可行区间 / 证据等级）</h4>
        <el-table :data="detailDlg.data.records" size="small" border>
          <el-table-column label="产品线" width="120">
            <template #default="{ row }">{{ row.productLineName }}</template>
          </el-table-column>
          <el-table-column label="分配点值" width="110">
            <template #default="{ row }">{{ fmtNum(row.record.qty) }}</template>
          </el-table-column>
          <el-table-column label="可行区间" width="170">
            <template #default="{ row }">
              [{{ fmtNum(row.record.qtyLow) }}, {{ fmtNum(row.record.qtyHigh) }}]
            </template>
          </el-table-column>
          <el-table-column label="不确定量" width="100">
            <template #default="{ row }">{{ fmtNum(row.record.uncertaintyQty) }}</template>
          </el-table-column>
          <el-table-column label="证据等级" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="gradeType(row.record.evidenceGrade)">
                {{ gradeLabel(row.record.evidenceGrade) }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>

        <h4>证据明细（可回查复算）</h4>
        <el-table :data="detailDlg.data.evidence" size="small" border>
          <el-table-column label="等级" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="gradeType(row.evidenceGrade)">
                {{ gradeLabel(row.evidenceGrade) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="refLabel" label="证据来源" width="240" show-overflow-tooltip />
          <el-table-column label="原始读数" width="110">
            <template #default="{ row }">{{ row.readingQty != null ? fmtNum(row.readingQty) : '-' }}</template>
          </el-table-column>
          <el-table-column label="计入量" width="110">
            <template #default="{ row }">{{ fmtNum(row.appliedQty) }}</template>
          </el-table-column>
          <el-table-column prop="detail" label="计算说明" show-overflow-tooltip />
        </el-table>

        <el-alert v-if="detailDlg.data.run.solverNote" type="info" :closable="false" style="margin-top:12px"
                  :title="`求解解释：${detailDlg.data.run.solverNote}`" />
        <el-alert v-if="detailDlg.data.run.feasible === false" type="error" :closable="false" style="margin-top:12px"
                  title="平衡求解无可行解：该运行不能固定为批次依据，请调整表计误差界或工程约束后重新生成" />
        <div v-if="detailDlg.data.run.status === 'DRAFT'" style="margin-top:14px;text-align:right">
          <el-button type="success" @click="doFix(detailDlg.data.run)">固定为批次分配依据</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 表计登记 -->
    <el-dialog v-model="meterDlg.visible" title="登记表计" width="520px">
      <el-form label-width="100px">
        <el-form-item label="表计编号"><el-input v-model="meterDlg.meterNo" style="width:260px" /></el-form-item>
        <el-form-item label="工厂">
          <el-select v-model="meterDlg.plantId" style="width:260px">
            <el-option v-for="p in store.plants" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="资源">
          <el-select v-model="meterDlg.energyType" style="width:260px">
            <el-option label="共用蒸汽" value="STEAM" />
            <el-option label="共用压缩空气" value="COMP_AIR" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="meterDlg.role">
            <el-radio value="MAIN">总表</el-radio>
            <el-radio value="SUB">子表</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="meterDlg.role === 'SUB'" label="归属产品线">
          <el-select v-model="meterDlg.productLineId" style="width:260px">
            <el-option v-for="l in store.lines" :key="l.id" :label="l.name" :value="l.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="误差界(%)">
          <el-input-number v-model="meterDlg.errorPct" :min="0" :precision="2" :controls="false" style="width:260px" />
        </el-form-item>
        <el-form-item label="校准证书"><el-input v-model="meterDlg.calibrationRef" style="width:260px" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="meterDlg.remark" style="width:260px" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="meterDlg.visible = false">取消</el-button>
        <el-button type="primary" @click="submitMeter">保存</el-button>
      </template>
    </el-dialog>

    <!-- 系数登记 -->
    <el-dialog v-model="coefDlg.visible" title="登记工程系数" width="520px">
      <el-form label-width="110px">
        <el-form-item label="工厂">
          <el-select v-model="coefDlg.plantId" style="width:260px">
            <el-option v-for="p in store.plants" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="产品线">
          <el-select v-model="coefDlg.productLineId" style="width:260px">
            <el-option v-for="l in store.lines" :key="l.id" :label="l.name" :value="l.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="资源">
          <el-select v-model="coefDlg.energyType" style="width:260px">
            <el-option label="共用蒸汽" value="STEAM" />
            <el-option label="共用压缩空气" value="COMP_AIR" />
          </el-select>
        </el-form-item>
        <el-form-item label="单位工时系数">
          <el-input-number v-model="coefDlg.coefficient" :min="0.000001" :precision="6" :controls="false" style="width:260px" />
        </el-form-item>
        <el-form-item label="测算来源"><el-input v-model="coefDlg.basis" style="width:260px" /></el-form-item>
        <el-form-item label="适用起始">
          <el-input v-model="coefDlg.validFrom" placeholder="YYYY-MM" style="width:120px" />
        </el-form-item>
        <el-form-item label="适用截止">
          <el-input v-model="coefDlg.validTo" placeholder="YYYY-MM，空=至今" style="width:200px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="coefDlg.visible = false">取消</el-button>
        <el-button type="primary" @click="submitCoef">保存</el-button>
      </template>
    </el-dialog>

    <!-- 工时登记 -->
    <el-dialog v-model="hourDlg.visible" title="登记工时台账" width="480px">
      <el-form label-width="100px">
        <el-form-item label="工厂">
          <el-select v-model="hourDlg.plantId" style="width:240px">
            <el-option v-for="p in store.plants" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="产品线">
          <el-select v-model="hourDlg.productLineId" style="width:240px">
            <el-option v-for="l in store.lines" :key="l.id" :label="l.name" :value="l.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="共用资源">
          <el-select v-model="hourDlg.energyType" style="width:240px">
            <el-option label="共用蒸汽" value="STEAM" />
            <el-option label="共用压缩空气" value="COMP_AIR" />
          </el-select>
        </el-form-item>
        <el-form-item label="期间"><el-input v-model="hourDlg.period" placeholder="YYYY-MM" style="width:240px" /></el-form-item>
        <el-form-item label="工时">
          <el-input-number v-model="hourDlg.hours" :min="0" :precision="3" :controls="false" style="width:240px" />
        </el-form-item>
        <el-form-item label="记录单号"><el-input v-model="hourDlg.sourceRef" style="width:240px" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="hourDlg.visible = false">取消</el-button>
        <el-button type="primary" @click="submitHour">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { store, fmtNum, fmtTime } from '../store'

const tab = ref('runs')
const runs = ref([])
const meters = ref([])
const coefs = ref([])
const hours = ref([])
const loadingRuns = ref(false)
const filters = reactive({ period: '', energyType: '' })

const createDlg = reactive({
  visible: false, saving: false, plantId: null, energyType: 'STEAM',
  period: '', method: 'HIERARCHY', fix: true, changeNote: ''
})
const detailDlg = reactive({ visible: false, data: null })
const meterDlg = reactive({
  visible: false, meterNo: '', plantId: null, energyType: 'STEAM', role: 'SUB',
  productLineId: null, errorPct: 1, calibrationRef: '', remark: ''
})
const coefDlg = reactive({
  visible: false, plantId: null, productLineId: null, energyType: 'STEAM',
  coefficient: 1, basis: '', validFrom: '', validTo: ''
})
const hourDlg = reactive({
  visible: false, plantId: null, productLineId: null, energyType: 'STEAM',
  period: '', hours: 100, sourceRef: ''
})

function allocStatusLabel(s) {
  return { DRAFT: '草稿', FIXED: '已固定', SUPERSEDED: '已取代' }[s] || s
}
function allocStatusType(s) {
  return { DRAFT: 'warning', FIXED: 'success', SUPERSEDED: 'info' }[s] || 'info'
}
function gradeLabel(g) {
  return { SUB_METER: '子表', COEF: '校准系数', WORK_HOUR: '工时', BALANCE: '平衡求解', UNALLOCATED: '未分配' }[g] || g
}
function gradeType(g) {
  return { SUB_METER: 'success', COEF: 'primary', WORK_HOUR: 'info', BALANCE: 'warning', UNALLOCATED: 'danger' }[g] || 'info'
}

async function loadRuns() {
  loadingRuns.value = true
  try {
    const params = {}
    if (filters.period) params.period = filters.period
    if (filters.energyType) params.energyType = filters.energyType
    runs.value = (await api.get('/allocations/runs', { params })).data
  } finally {
    loadingRuns.value = false
  }
}
async function loadMeters() { meters.value = (await api.get('/allocations/meters')).data }
async function loadCoefs() { coefs.value = (await api.get('/allocations/coefficients')).data }
async function loadHours() { hours.value = (await api.get('/allocations/work-hours')).data }

function openCreate() {
  Object.assign(createDlg, {
    visible: true, saving: false, plantId: store.plants[0]?.id, energyType: 'STEAM',
    period: '', method: 'HIERARCHY', fix: true, changeNote: ''
  })
}

async function submitCreate() {
  createDlg.saving = true
  try {
    const resp = await api.post('/allocations/runs', {
      plantId: createDlg.plantId, energyType: createDlg.energyType, period: createDlg.period,
      method: createDlg.method, fix: createDlg.fix, changeNote: createDlg.changeNote || null
    })
    ElMessage.success(createDlg.fix ? '分配运行已生成并固定' : '分配运行已生成（草稿）')
    createDlg.visible = false
    await loadRuns()
    if (resp.data.feasible === false) {
      ElMessage.warning('平衡求解无可行解，请打开证据详情查看求解解释')
    }
  } finally {
    createDlg.saving = false
  }
}

async function openDetail(row) {
  detailDlg.data = (await api.get(`/allocations/runs/${row.id}`)).data
  detailDlg.visible = true
}

async function doFix(row) {
  const { value } = await ElMessageBox.prompt('固定后该依据成为批次核算口径且不可改写，请输入差异说明（可留空）',
    `固定分配依据 ${row.runNo}`, { inputValue: row.changeNote || '', confirmButtonText: '确认固定' })
  await api.post(`/allocations/runs/${row.id}/fix`, { changeNote: value || null })
  ElMessage.success('已固定为批次分配依据；同期间旧依据已标记为被取代')
  detailDlg.visible = false
  loadRuns()
}

async function submitMeter() {
  await api.post('/allocations/meters', { ...meterDlg })
  ElMessage.success('表计已登记')
  meterDlg.visible = false
  loadMeters()
}

async function retireMeter(row) {
  const { value } = await ElMessageBox.prompt('停用原因（工艺改造后旧表停用，历史读数保留）',
    `停用表计 ${row.meterNo}`, { inputValue: '工艺改造停用' })
  await api.post(`/allocations/meters/${row.id}/retire`, { reason: value || '工艺改造停用' })
  ElMessage.success('表计已停用')
  loadMeters()
}

async function retireCoef(row) {
  const { value } = await ElMessageBox.prompt('旧系数适用截止期间（YYYY-MM，工艺改造后封死）',
    `停用系数 #${row.id}`, { inputValue: '' })
  await api.post(`/allocations/coefficients/${row.id}/retire`, { validTo: value || null, reason: '工艺改造停用' })
  ElMessage.success('系数已停用并封死适用期')
  loadCoefs()
}

async function submitCoef() {
  await api.post('/allocations/coefficients', { ...coefDlg })
  ElMessage.success('系数已登记')
  coefDlg.visible = false
  loadCoefs()
}

async function submitHour() {
  await api.post('/allocations/work-hours', { ...hourDlg })
  ElMessage.success('工时已登记')
  hourDlg.visible = false
  loadHours()
}

onMounted(() => {
  loadRuns(); loadMeters(); loadCoefs(); loadHours()
})
</script>

<style scoped>
.hint { color: #909399; font-size: 12px; }
h4 { margin: 16px 0 8px; color: #303133; }
</style>
