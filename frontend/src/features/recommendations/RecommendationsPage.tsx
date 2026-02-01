import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Search, Loader2 } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { getRecommendations, getPopularProducts } from '@/api/recommendation'
import { formatPrice, formatLatency } from '@/lib/utils'
import type { ProductRecommendation } from '@/api/types'

export function RecommendationsPage() {
  const [userId, setUserId] = useState('')
  const [searchUserId, setSearchUserId] = useState('')
  const [limit, setLimit] = useState(10)
  const [showPopular, setShowPopular] = useState(false)

  // 개인화 추천 쿼리
  const recommendationsQuery = useQuery({
    queryKey: ['recommendations', searchUserId, limit],
    queryFn: () => getRecommendations({ userId: searchUserId, limit }),
    enabled: !!searchUserId && !showPopular,
    staleTime: 60 * 1000, // 1분
  })

  // 인기 상품 쿼리
  const popularQuery = useQuery({
    queryKey: ['popular', limit],
    queryFn: () => getPopularProducts(limit),
    enabled: showPopular,
    staleTime: 60 * 1000,
  })

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault()
    setShowPopular(false)
    setSearchUserId(userId)
  }

  const handlePopular = () => {
    setShowPopular(true)
    setSearchUserId('')
  }

  const currentData = showPopular ? popularQuery.data : recommendationsQuery.data
  const isLoading = showPopular ? popularQuery.isLoading : recommendationsQuery.isLoading
  const isError = showPopular ? popularQuery.isError : recommendationsQuery.isError

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <div>
        <h2 className="text-2xl font-bold tracking-tight">추천 검색</h2>
        <p className="text-muted-foreground">
          유저 ID를 입력하여 개인화된 추천 상품을 확인하세요
        </p>
      </div>

      {/* 검색 폼 */}
      <Card>
        <CardHeader>
          <CardTitle>검색 조건</CardTitle>
          <CardDescription>유저 ID 또는 인기 상품을 조회합니다</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSearch} className="flex gap-4">
            <Input
              placeholder="유저 ID (예: USER-000001)"
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              className="max-w-sm"
            />
            <Input
              type="number"
              placeholder="개수"
              value={limit}
              onChange={(e) => setLimit(Number(e.target.value))}
              min={1}
              max={50}
              className="w-24"
            />
            <Button type="submit" disabled={!userId || isLoading}>
              {isLoading && !showPopular ? (
                <Loader2 className="h-4 w-4 animate-spin mr-2" />
              ) : (
                <Search className="h-4 w-4 mr-2" />
              )}
              검색
            </Button>
            <Button type="button" variant="outline" onClick={handlePopular} disabled={isLoading}>
              {isLoading && showPopular && <Loader2 className="h-4 w-4 animate-spin mr-2" />}
              인기 상품
            </Button>
          </form>
        </CardContent>
      </Card>

      {/* 결과 메타 정보 */}
      {currentData && (
        <div className="flex items-center gap-4">
          <Badge variant="secondary">전략: {currentData.strategy}</Badge>
          <Badge variant="outline">응답시간: {formatLatency(currentData.latencyMs)}</Badge>
          <Badge variant="outline">결과: {currentData.recommendations.length}개</Badge>
        </div>
      )}

      {/* 에러 상태 */}
      {isError && (
        <Card className="border-destructive">
          <CardContent className="pt-6">
            <p className="text-destructive">추천 조회에 실패했습니다. 백엔드 서버가 실행 중인지 확인하세요.</p>
          </CardContent>
        </Card>
      )}

      {/* 로딩 상태 */}
      {isLoading && (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
          {Array.from({ length: limit }).map((_, i) => (
            <Card key={i}>
              <CardContent className="pt-6">
                <Skeleton className="h-4 w-3/4 mb-2" />
                <Skeleton className="h-3 w-1/2 mb-4" />
                <Skeleton className="h-6 w-1/3" />
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* 결과 그리드 */}
      {currentData && currentData.recommendations.length > 0 && (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
          {currentData.recommendations.map((product) => (
            <ProductCard key={product.productId} product={product} />
          ))}
        </div>
      )}

      {/* 빈 결과 */}
      {currentData && currentData.recommendations.length === 0 && (
        <Card>
          <CardContent className="pt-6 text-center text-muted-foreground">
            추천 결과가 없습니다
          </CardContent>
        </Card>
      )}
    </div>
  )
}

function ProductCard({ product }: { product: ProductRecommendation }) {
  return (
    <Card className="hover:shadow-lg transition-shadow">
      <CardContent className="pt-6">
        <h3 className="font-medium text-sm line-clamp-2 mb-1">{product.productName}</h3>
        <p className="text-xs text-muted-foreground mb-2">{product.category}</p>
        <p className="text-lg font-bold text-primary">{formatPrice(product.price)}</p>
        {product.score > 0 && (
          <p className="text-xs text-muted-foreground mt-1">
            유사도: {(product.score * 100).toFixed(1)}%
          </p>
        )}
      </CardContent>
    </Card>
  )
}
