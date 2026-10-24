<template>
  <!-- 手机端占满整屏：46% 的宽度在手机上只剩一百多像素 -->
  <el-drawer
    v-model="visible"
    size="46%"
    destroy-on-close
  >
    <template #header>
      <div class="cf-drawer__head">
        <span>案件详情 · {{ detail.caseNo || '' }}</span>
        <!-- 重点关注：列表是主入口，但详情里也要能改（否则得退出去再点星）。
             星标是打在案件上的全局标记，只给管理层。 -->
        <template v-if="canManage && detail.id">
          <FocusStar :row="detail" />
          <span class="cf-muted" style="font-size: 12px">{{ detail.focus === 1 ? '重点关注中' : '未关注' }}</span>
          <!-- 编辑入口（2026-10-18 用户要求）：BOSS 要能改已录入的信息、补写案情。
               复用建案表单的编辑模式，改完立刻刷新详情并通知外层列表。 -->
          <el-button link type="primary" size="small" @click="editVisible = true">编辑案件</el-button>
        </template>
      </div>
    </template>

    <template v-if="detail.id">
      <!-- 手机上两列描述会挤成竖排的碎字，直接改单列 -->
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="案件名称" :span="2">{{ detail.name }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusMeta.type" size="small">{{ statusMeta.label }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="优先级">
          <span :style="{ color: priorityMeta.color, fontWeight: 600 }">{{ priorityMeta.label }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="案件类别">{{ detail.category || '-' }}</el-descriptions-item>
        <el-descriptions-item label="案卷类型">
          <el-tag v-if="detail.caseType" size="small" :type="(CASE_TYPE_META[detail.caseType] || {}).type || 'info'" effect="plain">
            {{ detail.caseTypeName || detail.caseType }}
          </el-tag>
          <span v-else class="cf-muted">未分类</span>
        </el-descriptions-item>
        <el-descriptions-item label="来源">{{ sourceLabel }}</el-descriptions-item>
        <el-descriptions-item label="案件编号">{{ detail.filingNo || '-' }}</el-descriptions-item>
        <!-- Excel 导入字段（2026-10-14）：只有导入进来的案件有值。
             手工建的案件这五项全空，整组不渲染，不占版面。 -->
        <template v-if="hasImportFields">
          <el-descriptions-item label="发案地点" :span="2">{{ detail.occurPlace || '-' }}</el-descriptions-item>
          <el-descriptions-item label="发案时间">{{ fmtOrDash(detail.occurTime) }}</el-descriptions-item>
          <el-descriptions-item label="承办单位">{{ detail.undertakeUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="受理时间">{{ fmtOrDash(detail.acceptTime) }}</el-descriptions-item>
          <el-descriptions-item label="处罚时间">{{ fmtOrDash(detail.punishTime) }}</el-descriptions-item>
        </template>
        <!-- 强制措施（2026-10-09）：建案表单与盯办共用同一个字段，这里如实显示 -->
        <el-descriptions-item label="强制措施">
          <el-tag v-if="detail.caseMeasure && detail.caseMeasure !== 'NONE'" size="small"
            :type="(MEASURE_META[detail.caseMeasure] || {}).type || 'info'" effect="plain">
            {{ (MEASURE_META[detail.caseMeasure] || {}).label || detail.caseMeasure }}
          </el-tag>
          <span v-else class="cf-muted">未采取</span>
        </el-descriptions-item>
        <!-- 期限：节点叫什么 + 哪天 + 提前多久提醒 -->
        <el-descriptions-item :label="detail.deadlineLabel || '截止期限'">
          <span :class="dueClass">{{ dueText(detail) }}</span>
          <el-tag v-if="detail.reminding" type="warning" size="small" effect="dark" style="margin-left: 6px">
            提醒中
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="提前提醒">
          <span v-if="detail.remindDays">提前 {{ detail.remindDays }} 天</span>
          <span v-else class="cf-muted">不提醒</span>
        </el-descriptions-item>
        <el-descriptions-item label="嫌疑人">
          <b>{{ detail.suspects?.length || 0 }}</b> 人
        </el-descriptions-item>
        <el-descriptions-item label="待办进度">
          <span v-if="detail.todoTotal">
            <b>{{ detail.todoDone || 0 }}</b> / {{ detail.todoTotal }} 项
          </span>
          <span v-else class="cf-muted">未设置</span>
        </el-descriptions-item>
        <el-descriptions-item label="创建人">{{ detail.createdByName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail.createdAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ detail.description || '-' }}</el-descriptions-item>
      </el-descriptions>

      <!-- 案件待办（含领导意见）：提意见/定级/落实反馈都在这一个面板，避免同一事项两处展示 -->
      <CaseTodoPanel :case-id="detail.id" :anchor="anchor" style="margin-top: 12px" @changed="reload" />

      <div v-if="detail.caseType === 'ADMINISTRATIVE' && canViewCloseRequest" class="cf-panel" style="margin-top: 12px">
        <div class="cf-panel__head">
          <span>行政案件结案申请</span>
          <el-button v-if="canRequestClose" type="primary" size="small" @click="closeDialogVisible = true">申请结案</el-button>
        </div>
        <template v-if="closeRequest">
          <p>处理结果：{{ closeOutcomeName(closeRequest.outcome) }} · {{ closeRequest.submittedByName }} 于 {{ closeRequest.submittedAt }} 提交</p>
          <p v-if="closeRequest.note" style="white-space: pre-wrap">处理说明：{{ closeRequest.note }}</p>
          <p v-if="closeRequest.operationPlatform">{{ closeOperationText }}</p>
          <p>审核状态：<el-tag :type="closeRequest.status === 'APPROVED' ? 'success' : closeRequest.status === 'RETURNED' ? 'danger' : 'warning'" size="small">{{ closeRequest.status === 'APPROVED' ? '已同意' : closeRequest.status === 'RETURNED' ? '已退回' : '待管理层审核' }}</el-tag></p>
          <p v-if="closeRequest.reviewComment">审核意见：{{ closeRequest.reviewComment }}</p>
          <div v-if="canManage && closeRequest.status === 'PENDING' && detail.status !== 'DONE'">
            <el-button type="success" :loading="closeReviewSaving" @click="reviewClose(true)">同意并办结</el-button>
            <el-button :loading="closeReviewSaving" @click="reviewClose(false)">退回申请</el-button>
          </div>
        </template>
        <span v-else class="cf-muted">尚无结案申请；经办人完成案件后可填写处理结果申请。</span>
      </div>

      <!-- 重要时间节点（2026-10-13）：增删改都在这里，与建案弹窗的批量录入互补。
           改完由后端 syncPrimary 派生回写 case_info 的期限三列，
           列表「截止期限」/到期提醒随之变化，不需要重新配置任何东西。 -->
      <div class="cf-panel" style="margin-top: 12px">
        <div class="cf-panel__head">
          <span>重要时间节点（{{ detail.timeNodes?.length || 0 }} 个）</span>
          <el-button link type="primary" @click="openNode()">新增节点</el-button>
        </div>
        <div class="cf-tscroll">
          <el-table :data="detail.timeNodes || []" size="small">
            <el-table-column label="节点" min-width="180">
              <template #default="{ row }">
                {{ row.nodeName }}
                <el-tag v-if="row.isPrimary" size="small" type="warning" effect="plain">列表显示</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="日期" width="160">
              <template #default="{ row }">
                {{ (row.nodeTimeText || '').slice(0, 10) }}
                <span v-if="row.daysLeft != null"
                  :style="{ color: row.daysLeft < 0 ? '#c0392b' : '#5a6472', marginLeft: '4px' }">
                  {{ row.daysLeft < 0 ? `逾期 ${-row.daysLeft} 天` : `剩 ${row.daysLeft} 天` }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="提前提醒" width="100">
              <template #default="{ row }">{{ row.remindDays ? `提前 ${row.remindDays} 天` : '不提醒' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openNode(row)">修改</el-button>
                <el-button link type="danger" @click="removeNode(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty><span class="cf-muted">暂无时间节点</span></template>
          </el-table>
        </div>
        <div v-if="detail.timeNodes?.length > 1" class="cf-muted"
          style="font-size: 12px; margin-top: 6px">
          多个节点时，列表「截止期限」与到期提醒只认<b>最近的一个</b>。
        </div>
      </div>

      <div class="cf-panel" style="margin-top: 12px">
        <div class="cf-panel__head">
          <span>嫌疑人（{{ detail.suspects?.length || 0 }} 人）</span>
          <el-button link type="primary" @click="openSuspect()">新增嫌疑人</el-button>
        </div>
        <div class="cf-tscroll">
          <el-table :data="detail.suspects || []" size="small">
            <el-table-column prop="name" label="姓名" width="90" />
            <el-table-column label="性别" width="60">
              <template #default="{ row }">{{ row.genderName || '-' }}</template>
            </el-table-column>
            <el-table-column prop="idCard" label="身份证号" min-width="170" show-overflow-tooltip />
            <!-- 强制措施（2026-10-18）：放在前几列，抽屉 46% 宽也能一屏看到，不用横向滚动 -->
            <el-table-column label="强制措施" width="186">
              <template #default="{ row }">
                <template v-if="row.measure && row.measure !== 'NONE'">
                  <el-tag size="small" :type="(MEASURE_META[row.measure] || {}).type || 'info'" effect="plain">
                    {{ row.measureName || (MEASURE_META[row.measure] || {}).label || row.measure }}
                  </el-tag>
                  <div class="cf-muted" style="font-size: 12px; line-height: 1.5">
                    {{ dateKeyOf(row.measureStartAt) }}
                    <template v-if="row.measureEndAt"> → {{ dateKeyOf(row.measureEndAt) }}</template>
                  </div>
                </template>
                <span v-else class="cf-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="phone" label="联系电话" width="120" />
            <el-table-column prop="address" label="住址" min-width="160" show-overflow-tooltip />
            <el-table-column label="操作" width="96" align="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openSuspect(row)">编辑</el-button>
                <el-button link type="danger" @click="removeSuspect(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty><span class="cf-muted">暂无嫌疑人信息</span></template>
          </el-table>
        </div>
      </div>

      <div class="cf-panel" style="margin-top: 12px">
        <div class="cf-panel__head">
          <span>现任承办人</span>
        </div>
        <div class="cf-tscroll">
          <el-table :data="currentAssignees" size="small">
            <el-table-column label="角色" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="row.assignRole === 'OWNER' ? 'danger' : 'info'">
                  {{ row.assignRole === 'OWNER' ? '主办' : '协办' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="employeeName" label="姓名" width="90" />
            <el-table-column prop="pathName" label="归属链路" min-width="200" />
            <el-table-column prop="note" label="指派要求" min-width="140" />
          </el-table>
        </div>
      </div>

      <div class="cf-panel" style="margin-top: 12px">
        <div class="cf-panel__head">
          <span>指派历史（改派留痕）</span>
          <span class="cf-muted">共 {{ detail.assignHistory?.length || 0 }} 条</span>
        </div>
        <div class="cf-tscroll">
          <el-table :data="detail.assignHistory || []" size="small" max-height="180">
            <el-table-column prop="employeeName" label="姓名" width="90" />
            <el-table-column label="角色" width="70">
              <template #default="{ row }">{{ row.assignRole === 'OWNER' ? '主办' : '协办' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <span :class="row.status === 'ACTIVE' ? 'cf-ok' : 'cf-muted'">
                  {{ row.status === 'ACTIVE' ? '现行' : '已改派' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="assignedAt" label="指派时间" width="160" />
          </el-table>
        </div>
      </div>

      <!-- 办理进度 = 全所操作留痕，仅管理层可见（后端 /logs/** 已同步拦截） -->
      <div v-if="canManage" class="cf-panel" style="margin-top: 12px">
        <!-- 办理进度默认收起：老案件动辄上百步，全部铺开会把详情抽屉撑得很长；
             需要追溯时点头部（或按钮）手动展开 -->
        <div class="cf-panel__head cf-progress__toggle" @click="progressExpanded = !progressExpanded">
          <span>办理进度</span>
          <span class="cf-muted">共 {{ progress.length }} 步</span>
          <span class="cf-spacer"></span>
          <el-button link type="primary" size="small">
            {{ progressExpanded ? '收起' : '展开查看' }}
          </el-button>
        </div>
        <el-collapse-transition>
          <div v-show="progressExpanded" style="padding: 14px 16px">
          <el-timeline v-if="progress.length">
            <el-timeline-item
              v-for="p in progress"
              :key="p.id"
              :timestamp="p.createdAtText"
              placement="top"
              :type="progressType(p.action)"
              :hollow="!!p.undone"
            >
              <div><b>{{ p.actionName }}</b>　{{ p.content }}</div>
              <div class="cf-muted">
                {{ p.operatorName }}<span v-if="p.undone">（此步已撤回）</span>
              </div>

              <!-- 批注（类似 Word 批注）：挂在进度下，展示批注人 / 批注时间 -->
              <div v-for="cmt in (commentsByLog[p.id] || [])" :key="cmt.id" class="cf-pcomment">
                <div class="cf-pcomment__head">
                  <span class="cf-pcomment__author">{{ cmt.creatorName }}</span>
                  <span class="cf-muted">{{ dateKeyOf(cmt.createdAt) }} 批注</span>
                  <span class="cf-spacer"></span>
                  <template v-if="canManage">
                    <el-button link type="primary" size="small" @click="editComment(cmt)">编辑</el-button>
                    <el-button link type="danger" size="small" @click="removeComment(cmt)">删除</el-button>
                  </template>
                </div>
                <div class="cf-pcomment__body">{{ cmt.content }}</div>
                <div v-if="cmt.updaterName" class="cf-muted" style="font-size: 12px; margin-top: 2px">
                  最后编辑：{{ cmt.updaterName }} · {{ dateKeyOf(cmt.updatedAt) }}
                </div>
              </div>

              <!-- 批注入口：仅管理层（管理员/领导）；办案人与普通用户只读 -->
              <el-button
                v-if="canManage && !p.undone"
                link type="primary" size="small" style="margin-top: 4px; padding: 0"
                @click="addComment(p)">
                {{ (commentsByLog[p.id] || []).length ? '追加批注' : '批注' }}
              </el-button>
            </el-timeline-item>
          </el-timeline>
          <span v-else class="cf-muted">暂无进度记录</span>
        </div>
        </el-collapse-transition>
      </div>
      <div class="cf-toolbar" style="margin-top: 14px">
        <el-button v-if="detail.caseType !== 'ADMINISTRATIVE' && detail.status === 'ASSIGNED'" type="primary" @click="changeStatus('IN_PROGRESS')">
          开始处理
        </el-button>
        <el-button
          v-if="canManage && detail.caseType !== 'ADMINISTRATIVE' && !['DONE', 'CANCELLED'].includes(detail.status)"
          type="success"
          @click="changeStatus('DONE')"
        >
          办结
        </el-button>
        <el-button
          v-if="canManage && !['DONE', 'CANCELLED'].includes(detail.status)"
          @click="changeStatus('CANCELLED')"
        >
          撤销
        </el-button>
        <span v-if="!canManage" class="cf-muted" style="font-size: 13px">{{ detail.caseType === 'ADMINISTRATIVE' ? '可提交结案申请，审核通过后办结' : '办结 / 撤销为管理层权限' }}</span>
        <span class="cf-spacer" />
        <el-button @click="visible = false">关闭</el-button>
      </div>
    </template>
  </el-drawer>

  <FeedbackDialog v-model="closeDialogVisible" title="申请行政案件结案" confirm-text="提交结案申请"
    :hide-status="true" :loading="closeSaving" @submit="submitCloseRequest">
    <el-form-item label="处理结果" required style="margin-top: 12px">
      <el-select v-model="closeOutcome" placeholder="请选择实际处理结果" style="width: 100%">
        <el-option label="调解" value="MEDIATION" />
        <el-option label="处罚" value="PENALTY" />
        <el-option label="不予处罚" value="NO_PENALTY" />
        <el-option label="终止" value="TERMINATED" />
      </el-select>
    </el-form-item>
  </FeedbackDialog>

  <!-- 时间节点录入：新增与修改共用（带 nodeId 即为修改） -->
  <el-dialog
    v-model="nodeVisible"
    :title="nodeForm.id ? '修改时间节点' : '新增时间节点'"
    width="520px"
    destroy-on-close
    class="cf-dialog"
  >
    <el-form :model="nodeForm" label-width="92px">
      <el-form-item label="节点类型">
        <el-select v-model="nodeForm.nodeKey" clearable placeholder="自定义（手动填名称）" style="width: 100%">
          <el-option v-for="p in TIME_NODE_PRESETS" :key="p.value" :label="p.label" :value="p.value" />
        </el-select>
        <div class="cf-form-tip">选预设类型时名称自动用标准名，便于按类型筛选统计</div>
      </el-form-item>
      <el-form-item v-if="!nodeForm.nodeKey" label="节点名称" required>
        <el-input v-model="nodeForm.nodeName" placeholder="如：移送起诉期限" maxlength="64" />
      </el-form-item>
      <el-form-item label="节点日期" required>
        <el-date-picker
          v-model="nodeDay"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="提前提醒">
        <el-select v-model="nodeForm.remindDays" placeholder="不提醒" clearable style="width: 100%">
          <el-option v-for="d in REMIND_DAY_PRESETS" :key="d" :label="`提前 ${d} 天`" :value="d" />
        </el-select>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="nodeForm.remark" type="textarea" :rows="2" maxlength="255" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="nodeVisible = false">取消</el-button>
      <el-button type="primary" :loading="nodeSaving" @click="submitNode">保存</el-button>
    </template>
  </el-dialog>

  <!-- 嫌疑人录入 / 编辑：同一个弹窗两用，title 按有无 id 切换。
       编辑要把「是否采取强制措施 / 何种措施 / 起止时间」如实回显，改完即存。 -->
  <el-dialog
    v-model="suspectVisible"
    :title="suspectForm.id ? '编辑嫌疑人' : '新增嫌疑人'"
    width="640px"
    destroy-on-close
    class="cf-dialog"
  >
    <el-form :model="suspectForm" label-width="92px">
      <el-form-item label="姓名" required>
        <el-input v-model="suspectForm.name" placeholder="必填" maxlength="64" />
      </el-form-item>
      <el-row :gutter="12">
        <el-col :span="12" :xs="24">
          <el-form-item label="性别">
            <el-select v-model="suspectForm.gender" clearable style="width: 100%">
              <el-option label="男" value="MALE" />
              <el-option label="女" value="FEMALE" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12" :xs="24">
          <el-form-item label="联系电话">
            <el-input v-model="suspectForm.phone" maxlength="32" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="身份证号">
        <el-input v-model="suspectForm.idCard" maxlength="32" />
      </el-form-item>
      <el-form-item label="住址">
        <el-input v-model="suspectForm.address" maxlength="255" />
      </el-form-item>
      <!-- 强制措施（2026-10-18 用户要求）：当前措施 + 措施开始时间 + 下一节点结束时间。
           选了措施就把两个时间一并要求填，缺一个都不给保存。 -->
      <!-- label 必须用「强制措施」(4 字)，不能用「当前强制措施」：
           label 栏内容区只有 80px，6 字需 84px，会把「施」挤到第二行并顶歪下面「措施开始」 -->
      <el-form-item label="强制措施">
        <el-select v-model="suspectForm.measure" clearable placeholder="未采取" style="width: 100%">
          <el-option v-for="m in MEASURE_CHOICES" :key="m.value" :label="m.label" :value="m.value" />
        </el-select>
      </el-form-item>
      <!-- 起止时间各自整行：两列并排时日期控件只剩 ~148px，日期和 placeholder 必被裁 -->
      <template v-if="suspectForm.measure">
        <el-form-item label="措施开始" required>
          <el-date-picker v-model="suspectForm.measureStartAt" type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择措施开始日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="措施结束" required>
          <el-date-picker v-model="suspectForm.measureEndAt" type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择下一节点日期" style="width: 100%" />
        </el-form-item>
      </template>
      <el-form-item label="备注">
        <el-input v-model="suspectForm.remark" type="textarea" :rows="2" maxlength="512" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="suspectVisible = false">取消</el-button>
      <el-button type="primary" :loading="suspectSaving" @click="submitSuspect">保存</el-button>
    </template>
  </el-dialog>

  <!-- 编辑案件（2026-10-18）：与案件列表共用同一个表单，编辑模式下时间节点走详情面板 -->
  <CaseFormDialog v-model="editVisible" :case-id="detail.id" @done="onEditDone" />
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElCollapseTransition } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { caseApi, fileApi, suspectApi, timeNodeApi, logApi, watchApi } from '../api'
import CaseTodoPanel from './CaseTodoPanel.vue'
import CaseFormDialog from './CaseFormDialog.vue'
import FeedbackDialog from './FeedbackDialog.vue'
import FocusStar from './FocusStar.vue'
import { STATUS_META, PRIORITY_META, SOURCE_META, DUE_META, CASE_TYPE_META, MEASURE_META, MEASURE_CHOICES,
  dueText, deadlineTextOf, dateKeyOf, TIME_NODE_PRESETS, REMIND_DAY_PRESETS } from '../utils/format'
import { useUserStore } from '../store/user'
import { useEventStore } from '../store/events'


const props = defineProps({
  modelValue: { type: Boolean, default: false },
  caseId: { type: [Number, null], default: null },
  /**
   * 定位锚点（2026-10-08 信箱「查看案件」用）。
   * { todoId, subtaskId }——由路由 query 透传，逐层下传到待办面板去定位高亮。
   */
  anchor: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'done'])

const userStore = useUserStore()
// 办结 / 撤销 = 结案动作，仅所长 / 副所长 / 法制员可操作
const canManage = computed(() => userStore.isFullAccess)

const visible = ref(false)
const detail = ref({})
/** 编辑案件弹窗（2026-10-18）：复用建案表单的编辑模式 */
const editVisible = ref(false)
const closeRequest = ref(null)
const closeDialogVisible = ref(false)
const closeSaving = ref(false)
const closeReviewSaving = ref(false)
const closeOutcome = ref('')
const closeOutcomeName = (value) => ({ MEDIATION: '调解', PENALTY: '处罚', NO_PENALTY: '不予处罚', TERMINATED: '终止' })[value] || value
const isCurrentHandler = computed(() => !!userStore.userInfo?.employeeId
  && [detail.value.owner, ...(detail.value.members || [])].some(a => a?.employeeId === userStore.userInfo.employeeId && a?.status === 'ACTIVE'))
const canViewCloseRequest = computed(() => canManage.value || isCurrentHandler.value)
const canRequestClose = computed(() => detail.value.caseType === 'ADMINISTRATIVE'
  && !['DONE', 'CANCELLED'].includes(detail.value.status)
  && closeRequest.value?.status !== 'PENDING'
  && isCurrentHandler.value)
const closeOperationText = computed(() => closeRequest.value?.operationPlatform
  ? '于 ' + (closeRequest.value.operationTime || closeRequest.value.submittedAt) + ' 在 '
    + closeRequest.value.operationPlatform + ' 执行了 ' + closeRequest.value.operationAction + ' 操作。' : '')

const submitCloseRequest = async (feedback) => {
  if (!closeOutcome.value) {
    ElMessage.warning('请选择实际处理结果')
    return
  }
  closeSaving.value = true
  try {
    closeRequest.value = await caseApi.requestClose(detail.value.id, {
      outcome: closeOutcome.value,
      note: feedback.note,
      uploadTime: feedback.uploadTime,
      uploadPlatform: feedback.uploadPlatform,
      uploadFile: feedback.uploadFile
    })
    closeDialogVisible.value = false
    closeOutcome.value = ''
    ElMessage.success('结案申请已提交，等待管理层审核')
    emit('done')
  } finally {
    closeSaving.value = false
  }
}

const reviewClose = async (approve) => {
  if (closeReviewSaving.value || !closeRequest.value) return
  let comment = ''
  try {
    if (approve) {
      await ElMessageBox.confirm('确认同意该申请并将行政案件办结？', '审核结案申请', { type: 'warning' })
    } else {
      const answer = await ElMessageBox.prompt('请填写退回原因', '退回结案申请', {
        inputType: 'textarea', inputValidator: (v) => v?.trim() ? true : '请填写退回原因',
        confirmButtonText: '退回', cancelButtonText: '取消'
      })
      comment = answer.value.trim()
    }
  } catch { return }
  closeReviewSaving.value = true
  try {
    await caseApi.reviewCloseRequest(detail.value.id, closeRequest.value.id, { approve, comment })
    ElMessage.success(approve ? '已审核并办结案件' : '申请已退回')
    await reload()
    emit('done')
  } finally {
    closeReviewSaving.value = false
  }
}

const changeStatus = async (status) => {
  if (status === 'DONE' || status === 'CANCELLED') {
    try {
      await ElMessageBox.confirm(status === 'DONE' ? '确认办结该案件？' : '确认撤销该案件？', '变更案件状态', { type: 'warning' })
    } catch { return }
  }
  await caseApi.status(detail.value.id, { status })
  ElMessage.success(status === 'DONE' ? '案件已办结' : status === 'CANCELLED' ? '案件已撤销' : '已开始处理')
  await reload()
  emit('done')
}

const statusMeta = computed(() => detail.value.caseType === 'ADMINISTRATIVE' && detail.value.status !== 'DONE'
  ? { label: '已受案', type: 'primary' }
  : STATUS_META[detail.value.status] || { label: '-', type: 'info' })
const priorityMeta = computed(() => PRIORITY_META[detail.value.priority] || { label: '-', color: '#646a73' })
const sourceLabel = computed(() => SOURCE_META[detail.value.sourceType] || detail.value.sourceType || '-')
// Excel 导入字段（2026-10-14）：五项里任一个有值就渲染整组
const hasImportFields = computed(() => !!(detail.value.occurPlace || detail.value.occurTime
  || detail.value.undertakeUnit || detail.value.acceptTime || detail.value.punishTime))
/** 时间列展示：ISO -> "yyyy-MM-dd HH:mm"；空值给横杠 */
const fmtOrDash = (t) => (t ? deadlineTextOf(t) : '-')
const dueClass = computed(() => {
  const meta = DUE_META[detail.value.dueLevel] || DUE_META.NONE
  return meta.color === 'danger' ? 'cf-danger' : meta.color === 'warn' ? 'cf-warn' : 'cf-muted'
})
const currentAssignees = computed(() => {
  const list = []
  if (detail.value.owner) list.push(detail.value.owner)
  list.push(...(detail.value.members || []))
  return list
})

watch(() => props.modelValue, async (v) => {
  visible.value = v
  if (v) progressExpanded.value = false   // 每次打开默认收起
  if (v && props.caseId) await reload()
})
watch(visible, (v) => emit('update:modelValue', v))

const reload = async () => {
  if (!props.caseId) return
  try {
    detail.value = await caseApi.detail(props.caseId)
    closeRequest.value = detail.value.caseType === 'ADMINISTRATIVE' && canViewCloseRequest.value
      ? await caseApi.latestCloseRequest(props.caseId) : null
  } catch (e) {
    // 案件已被删除，或当前登录人已经看不到它（转手后不再承办）：
    // 抽屉继续开着只会是一张空白卡，不如关掉并说清原因。
    // 信箱里留着的旧信件指到已删案件时，走的就是这条路径——
    // 不接住会变成控制台里一条没人管的 Promise 异常（2026-10-11 修）。
    detail.value = {}
    visible.value = false
    ElMessage.warning('该案件已被删除或无权查看')
    return
  }
  // 办理进度：仅管理层加载（普通员工面板已隐藏，接口也在后端拦截）
  if (canManage.value) {
    progress.value = await logApi.caseLogs(props.caseId)
  }
  // 批注随详情一并刷新（待办面板自己订阅 SSE 刷新，失败不阻塞主信息展示）
  try { comments.value = await watchApi.comments(props.caseId) } catch { /* 忽略 */ }
}

// ---- 全局监听：抽屉打开期间，其他入口推进本案件时进度时间线自动刷新 ----
const eventStore = useEventStore()
let progressTimer = null
let unsubscribe = null
const onCaseEvent = (e) => {
  if (!visible.value || !props.caseId) return
  if (e.module !== 'CASE' || String(e.targetId) !== String(props.caseId)) return
  // 1 秒内连续事件合并成一次刷新（批量指派/撤回等场景）
  if (progressTimer) return
  progressTimer = setTimeout(async () => {
    progressTimer = null
    if (!visible.value || !props.caseId) return
    const caseId = props.caseId
    try {
      await reload()
    } catch (err) { /* 案件可能已被删除，忽略 */ }
  }, 800)
}
onMounted(() => { unsubscribe = eventStore.subscribe(onCaseEvent) })
onBeforeUnmount(() => {
  clearTimeout(progressTimer)
  if (unsubscribe) unsubscribe()
})

// 办理进度时间线（操作日志按时间正序）；默认收起，展开才渲染
const progress = ref([])
const progressExpanded = ref(false)
const PROGRESS_TYPE = {
  CREATE: 'primary', UPDATE: 'info', ASSIGN: 'warning',
  STATUS: 'success', DELETE: 'danger', UNDO: 'info'
}
const progressType = (action) => PROGRESS_TYPE[action] || 'info'

// ---- 批注（功能1）：挂在进度时间线上，写操作仅管理层 ----
const comments = ref([])
const commentsByLog = computed(() => {
  const map = {}
  for (const c of comments.value) {
    (map[c.logId] = map[c.logId] || []).push(c)
  }
  return map
})

const addComment = async (logEntry) => {
  let content
  try {
    ({ value: content } = await ElMessageBox.prompt(
      `对进度「${logEntry.actionName}：${(logEntry.content || '').slice(0, 60)}」添加批注`,
      '添加批注', {
        inputType: 'textarea', inputPlaceholder: '批注内容，如：该步骤材料需补充完整后再推进',
        inputValidator: (v) => (v && v.trim()) ? true : '请填写批注内容',
        confirmButtonText: '提交批注', cancelButtonText: '取消'
      }))
  } catch { return }
  await watchApi.addComment(logEntry.id, content)
  ElMessage.success('批注已添加')
  await reload()
}

const editComment = async (cmt) => {
  let content
  try {
    ({ value: content } = await ElMessageBox.prompt('编辑批注内容', '编辑批注', {
      inputType: 'textarea', inputValue: cmt.content,
      inputValidator: (v) => (v && v.trim()) ? true : '请填写批注内容',
      confirmButtonText: '保存', cancelButtonText: '取消'
    }))
  } catch { return }
  await watchApi.updateComment(cmt.id, content)
  ElMessage.success('批注已更新')
  await reload()
}

const removeComment = async (cmt) => {
  try {
    await ElMessageBox.confirm(`确认删除这条批注？`, '删除批注', { type: 'warning' })
  } catch { return }
  await watchApi.removeComment(cmt.id)
  ElMessage.success('批注已删除')
  await reload()
}

// ---- 案件编辑（2026-10-18）----
/** 保存后详情立刻反映新值，同时通知外层列表重取（名称、时间、措施都可能变） */
const onEditDone = async () => {
  editVisible.value = false
  await reload()
  emit('done')
}

// ---- 嫌疑人 ----
const suspectVisible = ref(false)
const suspectSaving = ref(false)
const emptySuspect = () => ({
  id: null,
  name: '', gender: '', idCard: '', phone: '', address: '', remark: '',
  // 强制措施（2026-10-18）：选了措施就必须填起止时间，两处都会校验
  measure: '', measureStartAt: '', measureEndAt: ''
})
const suspectForm = ref(emptySuspect())

/** 不传 row = 新增；传 row = 编辑（把「是否采取 / 何种措施 / 起止时间」如实回显） */
const openSuspect = (row) => {
  suspectForm.value = row
    ? {
        id: row.id,
        name: row.name || '',
        gender: row.gender || '',
        idCard: row.idCard || '',
        phone: row.phone || '',
        address: row.address || '',
        remark: row.remark || '',
        // measure='NONE' 与未填等价，都回显成「未采取」（清空 select）
        measure: row.measure && row.measure !== 'NONE' ? row.measure : '',
        // VO 时间统一是 yyyy-MM-dd；防御性 slice 一下，存量带时分秒的数据也不会让 date-picker 报错
        measureStartAt: (row.measureStartAt || '').toString().slice(0, 10),
        measureEndAt: (row.measureEndAt || '').toString().slice(0, 10)
      }
    : emptySuspect()
  suspectVisible.value = true
}

const submitSuspect = async () => {
  const f = suspectForm.value
  if (!f.name || !f.name.trim()) {
    ElMessage.warning('请填写嫌疑人姓名')
    return
  }
  // 强制措施（2026-10-18）：选了就必须填起止时间——这两个时间就是办案期限本身，
  // 只记措施不记时间，到期提醒和期限统计都无从算起
  const taken = !!f.measure && f.measure !== 'NONE'
  if (taken && !f.measureStartAt) {
    ElMessage.warning('已选择强制措施，请填写措施开始时间')
    return
  }
  if (taken && !f.measureEndAt) {
    ElMessage.warning('已选择强制措施，请填写措施结束时间（下一节点）')
    return
  }
  if (taken && f.measureEndAt < f.measureStartAt) {
    ElMessage.warning('措施结束时间不能早于开始时间')
    return
  }
  suspectSaving.value = true
  try {
    if (f.id) {
      // 编辑：走后端已有的 PUT /cases/suspects/{id}（带快照日志，可撤回）
      await suspectApi.update(f.id, { ...f })
      ElMessage.success('嫌疑人信息已更新')
    } else {
      await suspectApi.add(detail.value.id, { ...f })
      ElMessage.success('嫌疑人已录入')
    }
    suspectVisible.value = false
    await reload()
    emit('done')
  } finally {
    suspectSaving.value = false
  }
}

const removeSuspect = async (row) => {
  await ElMessageBox.confirm(`确认删除嫌疑人「${row.name}」？`, '提示', { type: 'warning' })
  await suspectApi.remove(row.id)
  ElMessage.success('已删除')
  await reload()
  emit('done')
}

// ---- 重要时间节点（2026-10-13）----
const nodeVisible = ref(false)
const nodeSaving = ref(false)
/** 日期单独一个 ref（type=date），只传日期，后端归到当天 23:59:59 */
const nodeDay = ref('')
const emptyNode = () => ({ id: null, nodeKey: '', nodeName: '', remindDays: null, remark: '' })
const nodeForm = ref(emptyNode())

/** 不传 row = 新增；传 row = 修改（回显后端给的标准名，避免用户改写成别名） */
const openNode = (row) => {
  nodeForm.value = row
    ? { id: row.id, nodeKey: row.nodeKey || '', nodeName: row.nodeName || '', remindDays: row.remindDays || null, remark: row.remark || '' }
    : emptyNode()
  nodeDay.value = row ? (row.nodeTime || '').slice(0, 10) : ''
  nodeVisible.value = true
}

const submitNode = async () => {
  if (!nodeDay.value) {
    ElMessage.warning('请选择节点日期')
    return
  }
  // 自定义节点必须给名字；预设类型不需要（后端会填标准名）
  if (!nodeForm.value.nodeKey && !(nodeForm.value.nodeName || '').trim()) {
    ElMessage.warning('请填写节点名称，或选择一个预设节点类型')
    return
  }
  nodeSaving.value = true
  try {
    // 只传日期：后端统一归到当天 23:59:59（见 DayDate#endOfDayIfMidnight）
    const payload = {
      nodeKey: nodeForm.value.nodeKey || null,
      nodeName: nodeForm.value.nodeKey ? null : (nodeForm.value.nodeName || '').trim(),
      nodeTime: nodeDay.value,
      remindDays: nodeForm.value.remindDays || null,
      remark: nodeForm.value.remark || null
    }
    if (nodeForm.value.id) {
      await timeNodeApi.update(nodeForm.value.id, payload)
    } else {
      await timeNodeApi.add(detail.value.id, payload)
    }
    ElMessage.success(nodeForm.value.id ? '时间节点已修改' : '时间节点已添加')
    nodeVisible.value = false
    await reload()
    emit('done')
  } finally {
    nodeSaving.value = false
  }
}

const removeNode = async (row) => {
  await ElMessageBox.confirm(
    `确认删除时间节点「${row.nodeName}」？${(detail.value.timeNodes?.length || 0) > 1
      ? '删除后列表显示的截止期限会自动改为最近的其他节点。' : ''}`,
    '提示', { type: 'warning' })
  await timeNodeApi.remove(row.id)
  ElMessage.success('已删除')
  await reload()
  emit('done')
}
</script>

<style>
/* 办理进度折叠头：整行可点，热区大一点方便手抖的用户 */
.cf-progress__toggle { cursor: pointer; user-select: none }
.cf-progress__toggle:hover .cf-muted { color: #1b4a8c }

/* 批注卡（类似 Word 批注）：左竖线 + 浅底，挂在对应进度下方 */
.cf-pcomment {
  margin-top: 8px; padding: 8px 10px;
  background: #fbf7ec; border-left: 3px solid #d98a0b; border-radius: 3px;
}
.cf-pcomment__head { display: flex; align-items: center; gap: 8px; font-size: 12px }
.cf-pcomment__author { font-weight: 600; color: #1b2430 }
.cf-pcomment__body { font-size: 13px; color: #3d4654; margin-top: 3px; white-space: pre-wrap; word-break: break-all }
</style>
