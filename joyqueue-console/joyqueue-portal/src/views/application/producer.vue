<template>
  <div>
    <producer-base ref="producerBase" :keywordTip="keywordTip" :keywordName="keywordName" :colData="colData"
                   :subscribeDialogColData="subscribeDialog.colData" :showSummaryChart="true"  @on-enter="getList"
                   :search="search" :subscribeUrls="subscribeDialog.urls" @on-detail="handleDetail"/>
  </div>
</template>

<script>
import producerBase from '../monitor/producerBase.vue'
import {getAppCode, openOrCloseBtnRender, clientTypeSelectRender,
  clientTypeBtnRender} from '../../utils/common.js'

export default {
  name: 'producer',
  components: {
    producerBase
  },
  props: {
    search: {// 查询条件，格式：app:{id:0,code:''}
      type: Object
    }
  },
  data () {
    return {
      keywordTip: '请输入主题',
      keywordName: '主题',
      colData: [
        // {
        //   title: 'ID',
        //   key: 'id'
        // },
        {
          title: '应用',
          key: 'app.code',
          formatter (row) {
            return getAppCode(row.app, row.subscribeGroup)
          }
        },
        {
          title: '主题',
          key: 'topic.code'
        },
        {
          title: '命名空间',
          key: 'namespace.code'
        },
        // {
        //   title: '负责人',
        //   key: 'owner.code'
        // },
        {
          title: '连接数',
          key: 'connections'
        },
        {
          title: '入队数',
          key: 'enQuence.count'
        },
        {
          title: '生产权重',
          key: 'config.weight'
        },
        {
          title: '限制IP发送',
          key: 'config.blackList'
        },
        {
          title: '归档',
          key: 'config.archive',
          render: (h, params) => {
            return openOrCloseBtnRender(h, params.item.config === undefined ? undefined : params.item.config.archive)
          }
        },
        {
          title: '就近机房发送',
          key: 'config.nearBy',
          render: (h, params) => {
            return openOrCloseBtnRender(h, params.item.config === undefined ? undefined : params.item.config.nearBy)
          }
        },
        {
          title: '客户端类型',
          key: 'clientType',
          render: (h, params) => {
            return clientTypeBtnRender(h, params.item.clientType)
          }
        }
      ],
      // 订阅框
      subscribeDialog: {
        colData: [
          {
            title: '主题代码',
            key: 'code',
            width: '30%'
          },
          {
            title: '命名空间',
            key: 'namespace.code',
            width: '30%'
          },
          {
            title: '客户端类型',
            key: 'clientType',
            width: '30%',
            render: (h, params) => {
              return clientTypeSelectRender(h, params, this.$refs.producerBase.$refs.subscribe)
            }
          }
        ],
        urls: {
          add: `/producer/add`,
          search: `/topic/unsubscribed/search`
        }
      }
    }
  },
  methods: {
    getList () {
      this.$refs.producerBase.getList()
    },
    handleDetail (item) {
      this.$emit('on-detail', item)
    }
  },
  mounted () {
    // this.getList()
  }
}
</script>

<style scoped>

</style>
